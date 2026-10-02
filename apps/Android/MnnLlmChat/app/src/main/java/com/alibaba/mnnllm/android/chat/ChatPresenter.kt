// Modified by MNN Chat API contributors, 2026: local-only foreground inference service.
// Created by ruoyi.sjd on 2025/5/6.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.chat

import android.text.TextUtils
import com.alibaba.mnnllm.android.chat.background.BackgroundChatGeneration
import com.alibaba.mnnllm.android.chat.background.ChatGenerationCoordinator
import com.alibaba.mnnllm.android.chat.background.ChatGenerationService
import kotlinx.coroutines.Job
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.alibaba.mls.api.ModelItem
import com.alibaba.mnnllm.android.llm.ChatService
import com.alibaba.mnnllm.android.llm.ChatSession
import com.alibaba.mnnllm.api.openai.di.ServiceLocator
import com.alibaba.mnnllm.api.openai.manager.ServerEventManager
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.chat.model.ChatDataManager
import com.alibaba.mnnllm.android.chat.chatlist.ChatViewHolders
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.utils.FileUtils
import com.alibaba.mnnllm.android.model.ModelTypeUtils
import com.alibaba.mnnllm.android.model.ModelUtils
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Random

/**
 * ChatPresenter
 */
class ChatPresenter(
    private val chatActivity: ChatActivity,
    private var modelName: String,
    private var modelId: String
) {
    val dateFormat: DateFormat get() = chatActivity.dateFormat!!
    @Volatile var stopGenerating = false
    @Volatile private var destroyed = false
    @Volatile private var sessionId: String? = null
    private var sessionName:String? = null
    private var chatDataManager: ChatDataManager? = null
    private lateinit var chatSession: ChatSession
    private var runtimeLeaseEpoch: Long? = null
    private val presenterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val callbackGuard = com.alibaba.mnnllm.api.openai.runtime.ChatCallbackGuard()
    @Volatile private var backgroundAttachment: ChatGenerationCoordinator.Attachment<BackgroundChatGeneration.Lease>? = null
    private var backgroundObservation: Job? = null
    private val background get() = BackgroundChatGeneration.coordinator
    private val additionalListeners = mutableListOf<GenerateListener>()
    
    /**
     * Get LLM session instance
     * Provides safe access to the api.openai module
     * @return LlmSession instance, returns null if chatSession is not initialized or not of LlmSession type
     */
    fun getLlmSession(): com.alibaba.mnnllm.android.llm.LlmSession? {
        return if (isCurrentAttachment() && chatSession is com.alibaba.mnnllm.android.llm.LlmSession) {
            chatSession as com.alibaba.mnnllm.android.llm.LlmSession
        } else {
            null
        }
    }
    
    /**
     * Get current session ID
     * @return Session ID, returns null if not set
     */
    fun getSessionId(): String? {
        return sessionId
    }
    
    /**
     * Add an additional generate listener for multi-UI updates
     */
    fun addGenerateListener(listener: GenerateListener) {
        additionalListeners.add(listener)
    }
    
    /**
     * Remove an additional generate listener
     */
    fun removeGenerateListener(listener: GenerateListener) {
        additionalListeners.remove(listener)
    }

    init {
        chatDataManager = ChatDataManager.getInstance(chatActivity)
    }

    fun isCurrentAttachment(): Boolean = !destroyed && ::chatSession.isInitialized &&
        ServiceLocator.getLlmRuntimeController().isChatAttachmentCurrent(chatSession, runtimeLeaseEpoch) &&
        (backgroundAttachment?.let { background.owns(it) } != false)

    fun ownsBackgroundResponse(): Boolean = backgroundAttachment != null

    private fun retireBackground(): Boolean {
        val attachment = backgroundAttachment ?: return true
        if (!background.retire(attachment)) return false
        backgroundObservation?.cancel()
        backgroundAttachment = null
        return true
    }

    fun restoreBackgroundResponse(): Boolean {
        val attachment = backgroundAttachment ?: return false
        if (!background.owns(attachment)) return false
        chatActivity.chatListComponent.setup(modelName, BackgroundChatGeneration.history(attachment.job))
        observeBackground(attachment)
        return true
    }

    private fun observeBackground(attachment: ChatGenerationCoordinator.Attachment<BackgroundChatGeneration.Lease>) {
        backgroundObservation?.cancel()
        chatActivity.onGenerateStart(BackgroundChatGeneration.userItem(attachment.job))
        backgroundObservation = chatActivity.lifecycleScope.launch {
            var reportedFailure = false
            attachment.job.state.collect { snapshot ->
                if (!destroyed && backgroundAttachment === attachment && background.owns(attachment)) {
                    chatActivity.renderBackgroundGeneration(snapshot)
                    if (snapshot.phase == ChatGenerationCoordinator.Phase.FAILED && !reportedFailure) {
                        reportedFailure = true
                        chatActivity.showBackgroundGenerationFailure(snapshot.result["message"] as? String)
                    }
                }
            }
        }
    }

    fun getRuntimeLeaseEpoch(): Long? = runtimeLeaseEpoch

    fun createSession(): ChatSession {
        val intent = chatActivity.intent
        sessionId = intent.getStringExtra("chatSessionId")
        val configPath = intent.getStringExtra(if (ModelTypeUtils.isDiffusionModel(modelName)) "diffusionDir" else "configFilePath")
        check(!destroyed) { "Chat was closed before loading" }
        if (ServiceLocator.getLlmRuntimeController().canResumeChatSession(modelId, configPath)) {
            background.attach(modelId, sessionId)?.let { attachment ->
                backgroundAttachment = attachment
                chatSession = attachment.job.session.native
                runtimeLeaseEpoch = attachment.job.session.epoch
                sessionId = attachment.job.request.conversationId
                sessionName = attachment.job.request.text
                if (destroyed) {
                    background.detachObserver(attachment)
                    throw CancellationException("Chat was closed while attaching")
                }
                return chatSession
            }
        }
        return background.transition(isWanted = { !destroyed }) {
            // Read history after drain/persistence, including a response finished during config change.
            val history = sessionId?.takeIf { it.isNotBlank() }?.let { chatDataManager!!.getChatDataBySession(it) }
            sessionName = history?.firstOrNull()?.text
            val result = ServiceLocator.getLlmRuntimeController().ensureChatSession(
                modelId, modelName, configPath, sessionId, history, isCallerActive = { !destroyed }
            )
            runtimeLeaseEpoch = result.chatLeaseEpoch
            chatSession = result.session
            if (destroyed) {
                ServiceLocator.getLlmRuntimeController().detachChatSession(chatSession, runtimeLeaseEpoch,
                    com.alibaba.mnnllm.android.utils.PreferenceUtils.keepModelLoaded(chatActivity))
                throw CancellationException("Chat was closed while loading")
            }
            sessionId = chatSession.sessionId
            chatSession.setKeepHistory(true)
            chatSession
        }
    }

    fun load() {
        if (backgroundAttachment != null) { chatActivity.onLoadingChanged(false); return }
        Log.d(TAG, "current SessionId: $sessionId")
        val sessionForLoad = chatSession
        val leaseForLoad = runtimeLeaseEpoch
        presenterScope.launch {
            Log.d(TAG, "chatSession loading")
            chatActivity.lifecycleScope.launch {
                if (chatSession !== sessionForLoad || runtimeLeaseEpoch != leaseForLoad || !isCurrentAttachment()) return@launch
                chatActivity.onLoadingChanged(true)
            }
            try {
                ServiceLocator.getLlmRuntimeController().withChatAttachment(sessionForLoad, leaseForLoad) {
                    if (!sessionForLoad.isModelLoaded()) sessionForLoad.load()
                }
                chatActivity.lifecycleScope.launch {
                    if (chatSession !== sessionForLoad || runtimeLeaseEpoch != leaseForLoad || !isCurrentAttachment()) return@launch
                    chatActivity.onLoadingChanged(false)
                }
                Log.d(TAG, "chatSession loaded")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Model load failed: ${e.message}", e)
                ServiceLocator.getLlmRuntimeController().detachChatSession(sessionForLoad, leaseForLoad, false)
                chatActivity.lifecycleScope.launch {
                    if (chatSession !== sessionForLoad || runtimeLeaseEpoch != leaseForLoad || !isCurrentAttachment()) return@launch
                    chatActivity.onLoadingChanged(false)
                    chatActivity.onModelLoadFailed(e.message ?: "Model load failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed with unexpected error", e)
                ServiceLocator.getLlmRuntimeController().detachChatSession(sessionForLoad, leaseForLoad, false)
                chatActivity.lifecycleScope.launch {
                    if (chatSession !== sessionForLoad || runtimeLeaseEpoch != leaseForLoad || !isCurrentAttachment()) return@launch
                    chatActivity.onLoadingChanged(false)
                    chatActivity.onModelLoadFailed(e.message ?: "Model load failed")
                }
            }
        }
    }

    fun reset(onResetSuccess: (newSessionId: String) -> Unit) {
        if (!isCurrentAttachment()) return
        if (!retireBackground()) return
        val sessionToReset = chatSession
        val leaseToReset = runtimeLeaseEpoch
        val callbackToken = callbackGuard.beginReset() ?: return
        chatActivity.onLoadingChanged(true)
        stopGenerate()
        presenterScope.launch {
            try {
                val newId = ServiceLocator.getLlmRuntimeController().withChatAttachment(sessionToReset, leaseToReset) {
                    sessionToReset.reset()
                }
                chatActivity.lifecycleScope.launch {
                    callbackGuard.completeReset(callbackToken) {
                        if (!destroyed && chatSession === sessionToReset && runtimeLeaseEpoch == leaseToReset &&
                            ServiceLocator.getLlmRuntimeController().isChatAttachmentCurrent(sessionToReset, leaseToReset)) {
                            sessionId = newId
                            com.alibaba.mnnllm.android.chat.background.ResidentModelStatus.refreshConversation(sessionToReset)
                            sessionName = null
                            onResetSuccess(newId)
                            chatActivity.onLoadingChanged(false)
                        }
                    }
                }
            } catch (_: IllegalStateException) {
                chatActivity.lifecycleScope.launch {
                    callbackGuard.completeReset(callbackToken) {
                        if (isCurrentAttachment()) chatActivity.onLoadingChanged(false)
                    }
                }
            }
        }
    }

    private fun submitDiffusionRequest(input: String, userData: ChatDataItem, callbacks: GenerateListener): HashMap<String, Any> {
        val prompt = resolveDiffusionPrompt(input, modelId)
        val diffusionDestPath = FileUtils.generateDestDiffusionFilePath(
            chatActivity,
            sessionId!!
        )
        val imageInputPath = userData.imageUris?.firstOrNull()?.let { 
            FileUtils.getPathForUri(it)
        } ?: ""

        val config = ModelConfig.loadConfig(modelId)
        val steps = config?.diffusionSteps ?: ModelConfig.defaultConfig.diffusionSteps ?: 20
        val seed = if (config?.diffusionSeed != null && config.diffusionSeed!! != -1L) {
            config.diffusionSeed!!.toInt()
        } else {
            Random(System.currentTimeMillis()).nextInt()
        }
        val cfgPrompt = config?.cfgPrompt ?: "Generate high quality image"
        
        return chatSession.generate(
            prompt,
            mapOf(
                "output" to diffusionDestPath,
                "imageInput" to imageInputPath,
                "iterNum" to steps,
                "randomSeed" to seed,
                "cfgPrompt" to cfgPrompt
            )
            , object : GenerateProgressListener {
                override fun onProgress(progress: String?): Boolean {
                    callbacks.onDiffusionGenerateProgress(progress, diffusionDestPath)
                    return false
                }
            }
        )
    }

    private fun submitLlmRequest(prompt:String, callbacks: GenerateListener): HashMap<String, Any> {
        val generateResultProcessor =
            GenerateResultProcessor()
        generateResultProcessor.generateBegin()
        val result = chatSession.generate(prompt, mapOf(), object: GenerateProgressListener {
            override fun onProgress(progress: String?): Boolean {
                generateResultProcessor.process(progress)
                callbacks.onLlmGenerateProgress(progress, generateResultProcessor)
                if (stopGenerating) {
                    Log.d(TAG, "stopGenerating requested")
                }
                return stopGenerating
            }
        })
        result["response"] = generateResultProcessor.getRawResult()
        return result
    }

    private fun submitRequest(input: String, userData: ChatDataItem, callbacks: GenerateListener): HashMap<String, Any> {
        stopGenerating = false
        val benchMarkResult = try {
            if (ModelTypeUtils.isDiffusionModel(this.modelName)) {
                submitDiffusionRequest(input, userData, callbacks)
            } else {
                submitLlmRequest(input, callbacks)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation request", e)
            // Create a basic error result to ensure onGenerateFinished is called
            HashMap<String, Any>().apply {
                put("error", true)
                put("message", e.message ?: "Generation failed")
                put("response", "生成失败，请重试")
            }
        }
        
        callbacks.onGenerateFinished(benchMarkResult)
        return benchMarkResult
    }

    private fun updateSession(sessionId: String, modelId: String?, sessionName: String) {
        chatDataManager!!.addOrUpdateSession(sessionId, modelId)
        chatDataManager!!.updateSessionName(sessionId, sessionName)
    }


    suspend fun requestGenerate(userData: ChatDataItem, generateListener: GenerateListener? = null, allowBackground: Boolean = true): HashMap<String, Any> {
        if (allowBackground && isCurrentAttachment() && generateListener == null && additionalListeners.isEmpty() &&
            !ModelTypeUtils.isMultiModalModel(modelId) && !ModelTypeUtils.isMultiModalModel(modelName) &&
            userData.imageUris.isNullOrEmpty() && userData.audioUri == null && userData.videoUri == null &&
            !userData.text.isNullOrBlank()) {
            return requestBackgroundText(userData)
        }
        if (!retireBackground()) return hashMapOf("error" to true, "message" to "A chat response is still finishing")
        if (!isCurrentAttachment()) return hashMapOf("error" to true, "message" to "This chat is no longer attached to the model")
        val sessionForRequest = chatSession
        val leaseForRequest = runtimeLeaseEpoch
        val token = callbackGuard.tryNext { sessionId } ?: return hashMapOf(
            "error" to true, "message" to "Conversation reset is still in progress"
        )
        val listeners = listOf(generateListener ?: DefaultChatActivityListener(userData, token.conversationId)) + additionalListeners.toList()
        fun post(action: (GenerateListener) -> Unit) {
            chatActivity.lifecycleScope.launch {
                callbackGuard.runIfCurrent(token) {
                    if (!destroyed && chatSession === sessionForRequest && runtimeLeaseEpoch == leaseForRequest &&
                        sessionId == token.conversationId && ServiceLocator.getLlmRuntimeController()
                            .isChatAttachmentCurrent(sessionForRequest, leaseForRequest)) {
                        listeners.forEach(action)
                    }
                }
            }
        }
        val callbacks = object : GenerateListener {
            override fun onGenerateStart() = post { it.onGenerateStart() }
            override fun onGenerateFinished(result: HashMap<String, Any>) = post { it.onGenerateFinished(result) }
            override fun onLlmGenerateProgress(progress: String?, processor: GenerateResultProcessor) =
                post { it.onLlmGenerateProgress(progress, processor) }
            override fun onDiffusionGenerateProgress(progress: String?, path: String?) =
                post { it.onDiffusionGenerateProgress(progress, path) }
        }
        val prompt = PromptUtils.generateUserPrompt(userData)
        var userInputSaved = false

        // Ensure user input is saved first
        try {
            if (this.sessionName.isNullOrEmpty()) {
                this.sessionName = SessionUtils.generateSessionName(userData)
                updateSession(token.conversationId!!, modelId, sessionName!!)
            }
            
            // Always save user input to database first
            Log.d(TAG, "requestGenerate: saving user input for sessionId=$sessionId")
            chatDataManager!!.addChatData(token.conversationId, userData)
            userInputSaved = true
            
            callbacks.onGenerateStart()
            
            val result = presenterScope.async {
                return@async ServiceLocator.getLlmRuntimeController().withChatAttachment(sessionForRequest, leaseForRequest) {
                    if (!callbackGuard.isCurrent(token)) throw CancellationException("Conversation was replaced before generation")
                    submitRequest(prompt, userData, callbacks)
                }
            }.await()
            
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "requestGenerate: Error during request generation", e)

            // Still try to save user input even if generation fails
            try {
                if (!userInputSaved && token.conversationId != null) {
                    chatDataManager!!.addChatData(token.conversationId, userData)
                }
            } catch (saveException: Exception) {
                Log.e(TAG, "requestGenerate: Failed to save user input", saveException)
            }
            
            // Return error result
            val errorResult = HashMap<String, Any>().apply {
                put("error", true)
                put("message", e.message ?: "Request generation failed")
                put("response", "生成失败，请重试")
            }
            
            // Still call onGenerateFinished to ensure UI is updated
            callbacks.onGenerateFinished(errorResult)
            
            return errorResult
        }
    }

    private suspend fun requestBackgroundText(userData: ChatDataItem): HashMap<String, Any> {
        // Admission and FGS startup happen synchronously from the visible Send action, before await.
        if (callbackGuard.tryNext { sessionId } == null) return hashMapOf("error" to true, "message" to "Conversation reset is still in progress")
        val conversation = sessionId ?: return hashMapOf("error" to true)
        val attachment = try {
            background.admit(ChatGenerationCoordinator.Request(conversation, modelId, modelName,
                chatActivity.intent.getStringExtra("configFilePath"), userData.text!!, userData.time),
                BackgroundChatGeneration.Lease(chatSession, runtimeLeaseEpoch, conversation))
        } catch (_: IllegalStateException) { return hashMapOf("error" to true, "message" to "A chat response is still finishing") }
        backgroundAttachment = attachment
        observeBackground(attachment)
        chatActivity.requestBackgroundNotificationPermission()
        try { ChatGenerationService.start(chatActivity, attachment.job.id) }
        catch (_: Exception) { chatActivity.showBackgroundStartFailure() }
        return HashMap(attachment.job.completion.await())
    }

    fun stopGenerate() {
        backgroundAttachment?.let { background.stop(it.job.id, it.observer); return }
        stopGenerating = true
    }
    
    /**
     * Default GenerateListener that handles ChatActivity UI updates
     * This ensures all UI callbacks are executed on the main thread
     */
    // The per-request callback wrapper dispatches once to Main and validates its captured token.
    private inner class DefaultChatActivityListener(
        private val userData: ChatDataItem, private val conversationId: String?
    ) : GenerateListener {
        override fun onGenerateStart() = chatActivity.onGenerateStart(userData)
        override fun onLlmGenerateProgress(progress: String?, generateResultProcessor: GenerateResultProcessor) =
            chatActivity.onLlmGenerateProgress(progress, generateResultProcessor)
        override fun onDiffusionGenerateProgress(progress: String?, diffusionDestPath: String?) =
            chatActivity.onDiffusionGenerateProgress(progress, diffusionDestPath)
        override fun onGenerateFinished(benchMarkResult: HashMap<String, Any>) =
            chatActivity.onGenerateFinished(benchMarkResult, conversationId)
    }

    /**
     * Send a text message - unified method for both regular and voice messages
     * This ensures proper session management and database storage
     */
    suspend fun sendMessage(text: String): HashMap<String, Any> {
        val userData = ChatDataItem(ChatViewHolders.USER)
        userData.text = text
        userData.time = dateFormat.format(java.util.Date())
        return requestGenerate(userData)
    }
    
    /**
     * Send a pre-created ChatDataItem - for more complex message types
     */
    suspend fun sendMessage(userData: ChatDataItem): HashMap<String, Any> {
        return requestGenerate(userData)
    }

    fun destroy() {
        callbackGuard.invalidate()
        destroyed = true
        backgroundObservation?.cancel()
        val backgroundOwned = backgroundAttachment
        if (backgroundOwned != null) background.detachObserver(backgroundOwned) else stopGenerate()
        presenterScope.cancel("ChatPresenter destroy")
        if (backgroundOwned != null) return
        val sessionToRelease = if (::chatSession.isInitialized) chatSession else null
        val leaseToRelease = runtimeLeaseEpoch
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                if (sessionToRelease != null) {
                    ServiceLocator.getLlmRuntimeController().detachChatSession(sessionToRelease, leaseToRelease,
                        com.alibaba.mnnllm.android.utils.PreferenceUtils.keepModelLoaded(chatActivity))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during final chat session cleanup", e)
            }
        }
    }

    fun saveResponseToDatabase(recentItem: ChatDataItem, conversationId: String? = sessionId) {
        try {
            Log.d(TAG, "saveResponseToDatabase: saving response for sessionId=$sessionId")
            this.chatDataManager?.addChatData(conversationId, recentItem)
        } catch (e: Exception) {
            Log.e(TAG, "saveResponseToDatabase: Failed to save response to database for sessionId=$sessionId", e)
        }
    }

    fun mutateSession(action: (ChatSession) -> Unit) {
        if (!isCurrentAttachment()) return
        val session = chatSession
        val epoch = runtimeLeaseEpoch
        presenterScope.launch {
            try { ServiceLocator.getLlmRuntimeController().withChatAttachment(session, epoch) { action(session) } }
            catch (_: IllegalStateException) { /* A newer attachment owns this runtime. */ }
        }
    }

    fun setEnableAudioOutput(enable: Boolean) = mutateSession { it.setEnableAudioOutput(enable) }

    suspend fun prepareBenchmarkMessage(expected: ChatSession, epoch: Long?) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        check(retireBackground()) { "A chat response is still finishing" }
        ServiceLocator.getLlmRuntimeController().withChatAttachment(expected, epoch) {
            expected.setKeepHistory(false)
            expected.reset().also {
                sessionId = it
                com.alibaba.mnnllm.android.chat.background.ResidentModelStatus.refreshConversation(expected)
            }
        }
    }

    /**
     * Switch to a new model while preserving chat history
     */
    fun switchModel(
        newModelItem: ModelItem,
        currentChatHistory: List<ChatDataItem>,
        onSwitchComplete: (ChatSession) -> Unit,
        onSwitchError: (Exception) -> Unit,
        onSessionCreated: (ChatSession) -> Unit
    ) {
        val switchToken = callbackGuard.beginReset() ?: return
        stopGenerate()
        backgroundObservation?.cancel()
        backgroundAttachment = null
        val hosting = com.alibaba.mnnllm.android.chat.background.ResidentModelStatus.beginLoading(newModelItem.modelId!!, newModelItem.modelName!!)
        try { ChatGenerationService.startHosting(chatActivity, hosting.token) } catch (_: Exception) { chatActivity.showBackgroundStartFailure() }
        presenterScope.launch {
            try {
                chatActivity.lifecycleScope.launch {
                    chatActivity.onLoadingChanged(true)
                }
                val oldSessionId = sessionId
                destroyCurrentSession()
                val newSession = createNewModelSession(newModelItem, currentChatHistory)
                updateDatabaseForModelSwitch(oldSessionId, newModelItem.modelId!!)
                chatActivity.lifecycleScope.launch {
                    if (isCurrentAttachment()) onSessionCreated(newSession)
                }
                ServiceLocator.getLlmRuntimeController().withChatAttachment(newSession, runtimeLeaseEpoch) {
                    if (!newSession.isModelLoaded()) newSession.load()
                }
                chatActivity.lifecycleScope.launch {
                    callbackGuard.completeReset(switchToken) {
                        if (isCurrentAttachment()) {
                            onSwitchComplete(newSession)
                            chatActivity.onLoadingChanged(false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error switching model", e)
                chatActivity.lifecycleScope.launch {
                    callbackGuard.completeReset(switchToken) {
                        if (!destroyed) {
                            onSwitchError(e)
                            chatActivity.onLoadingChanged(false)
                        }
                    }
                }
            } finally { com.alibaba.mnnllm.android.chat.background.ResidentModelStatus.finishLoading(hosting.token) }
        }
    }
    
    private fun destroyCurrentSession() {
        if (::chatSession.isInitialized) {
            ServiceLocator.getLlmRuntimeController().detachChatSession(chatSession, runtimeLeaseEpoch, false)
        }
    }

    private fun createNewModelSession(newModelItem: ModelItem, currentChatHistory: List<ChatDataItem>): ChatSession {
        check(!destroyed) { "Chat was closed" }
        val result = ServiceLocator.getLlmRuntimeController().ensureChatSession(
            newModelItem.modelId!!, newModelItem.modelName!!, ModelUtils.getConfigPathForModel(newModelItem),
            null, currentChatHistory, true, isCallerActive = { !destroyed }
        )
        chatSession = result.session
        runtimeLeaseEpoch = result.chatLeaseEpoch
        modelId = newModelItem.modelId!!
        modelName = newModelItem.modelName!!
        sessionId = result.session.sessionId
        if (destroyed) {
            ServiceLocator.getLlmRuntimeController().detachChatSession(chatSession, runtimeLeaseEpoch,
                com.alibaba.mnnllm.android.utils.PreferenceUtils.keepModelLoaded(chatActivity))
            throw CancellationException("Chat was closed while switching")
        }
        return chatSession
    }

    private fun updateDatabaseForModelSwitch(oldSessionId: String?, newModelId: String) {
        if (oldSessionId != null) {
            chatDataManager?.updateSessionModelId(oldSessionId, newModelId)
        }
    }
    
    companion object {
        private const val TAG: String = "ChatPresenter"
        internal fun resolveDiffusionPrompt(input: String, modelId: String): String {
            if (input.isNotBlank()) return input
            return if (ModelTypeUtils.requiresFaceImageInput(modelId)) {
                DEFAULT_SANA_PROMPT
            } else {
                "A cyberpunk cat in neon lights"
            }
        }
    }

    interface GenerateListener {
        fun onDiffusionGenerateProgress(progress: String?, diffusionDestPath: String?)
        fun onGenerateStart()
        fun onGenerateFinished(benchMarkResult: HashMap<String, Any>)
        fun onLlmGenerateProgress(progress: String?, generateResultProcessor: GenerateResultProcessor)
    }
}
