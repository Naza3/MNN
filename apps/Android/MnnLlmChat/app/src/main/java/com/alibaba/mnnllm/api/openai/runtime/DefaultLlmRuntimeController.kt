// Modified by MNN Chat API contributors, 2026: exclusive API ownership and retained chat models.
package com.alibaba.mnnllm.api.openai.runtime

import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.llm.ChatService
import com.alibaba.mnnllm.android.llm.ChatSession
import com.alibaba.mnnllm.android.llm.LlmSession
import com.alibaba.mnnllm.android.model.ModelUtils
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import java.io.File
import com.alibaba.mnnllm.android.chat.background.BackgroundChatGeneration
import com.alibaba.mnnllm.android.chat.background.ResidentModelStatus
import java.security.MessageDigest
import java.util.UUID

object DefaultLlmRuntimeController : LlmRuntimeController {
    private val runtime = ResidentChatRuntime<ChatSession>(
        available = { !RuntimeOwnership.gate.isApiReserved() },
        cancelGeneration = { it.cancelGeneration() },
        detachUi = { it.detachUi() },
        release = {
            try { it.release(); ResidentModelStatus.released(it) }
            catch (error: Throwable) { ResidentModelStatus.cleanupFailed(it); throw error }
        },
        retainOnAbandon = { com.alibaba.mnnllm.android.utils.PreferenceUtils.keepModelLoaded(com.alibaba.mls.api.ApplicationProvider.get()) }
    )
    @Volatile private var residentModelId: String? = null
    @Volatile private var residentConfiguration: String? = null
    @Volatile private var apiModelId: String? = null

    override fun ensureChatSession(modelId: String, modelName: String, configPath: String?,
        sessionId: String?, historyList: List<ChatDataItem>?, forceReload: Boolean,
        isCallerActive: () -> Boolean): EnsureChatSessionResult {
        val path = configPath ?: ModelUtils.getConfigPathForModel(modelId)
            ?: error("MODEL_CONFIG_NOT_FOUND")
        val attachment = acquire(modelId, modelName, path, true, sessionId, historyList, forceReload, false, isCallerActive)
        return EnsureChatSessionResult(attachment.session, attachment.epoch)
    }

    override fun ensureSession(modelId: String, forceReload: Boolean, useAppConfig: Boolean,
        configPath: String?, sessionId: String?, historyList: List<ChatDataItem>?, deferLoad: Boolean): EnsureSessionResult {
        if (RuntimeOwnership.gate.isApiReserved()) return EnsureSessionResult(false, reason = "API_OWNS_RUNTIME")
        val path = configPath ?: (if (useAppConfig) ModelUtils.getConfigPathForModel(modelId)
            else ModelConfig.getDefaultConfigFile(modelId))
            ?: return EnsureSessionResult(false, reason = "MODEL_CONFIG_NOT_FOUND")
        return try {
            val attachment = acquire(modelId, ModelUtils.getModelName(modelId) ?: modelId, path,
                useAppConfig, sessionId, historyList, forceReload, deferLoad)
            val llm = attachment.session as? LlmSession ?: run {
                runtime.detach(attachment.session, attachment.epoch, false)
                return EnsureSessionResult(false, reason = "SESSION_NOT_LLM")
            }
            EnsureSessionResult(true, llm, modelId, chatLeaseEpoch = attachment.epoch)
        } catch (_: Exception) {
            EnsureSessionResult(false, reason = "SESSION_INIT_FAILED")
        }
    }

    private fun acquire(modelId: String, modelName: String, path: String, appConfig: Boolean,
        sessionId: String?, history: List<ChatDataItem>?, force: Boolean, deferLoad: Boolean,
        isCallerActive: () -> Boolean = { true }): ResidentChatRuntime.Attachment<ChatSession> =
        BackgroundChatGeneration.coordinator.transition(isCallerActive) {
        val id = sessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        // A changed config must take effect on re-entry; never quietly reuse stale backend/model settings.
        val key = "$modelId|$path|$appConfig|${configFingerprint(modelId, path, appConfig)}"
        check(isCallerActive()) { "Chat was closed while reading configuration" }
        runtime.acquire(key, create = {
            ChatService.provide().createSession(modelId, modelName, id, history, path,
                useNewConfig = !appConfig, useCustomConfig = appConfig)
        }, isWanted = isCallerActive, forceReload = force, prepare = { session, reused ->
            if (reused) session.attachConversation(id, history)
            session.setKeepHistory(true)
            if (!deferLoad) session.load()
            residentModelId = modelId
        }).also { residentConfiguration = key; ResidentModelStatus.loaded(modelId, modelName, path, it.session) }
    }

    private fun configFingerprint(modelId: String, path: String, appConfig: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val configFile = File(path).let { if (it.isDirectory) File(it, "config.json") else it }
        val files = mutableListOf(configFile)
        if (appConfig) files += File(ModelConfig.getExtraConfigFile(modelId))
        files.filter { it.isFile }.forEach { file -> file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun canResumeChatSession(modelId: String, configPath: String?): Boolean {
        val path = configPath ?: ModelUtils.getConfigPathForModel(modelId) ?: return false
        return residentConfiguration == "$modelId|$path|true|${configFingerprint(modelId, path, true)}"
    }
    override fun isChatAttachmentCurrent(session: ChatSession?, epoch: Long?): Boolean = runtime.isCurrent(session, epoch)
    override fun <T> withChatAttachment(session: ChatSession, epoch: Long?, action: () -> T): T =
        runtime.withAttachment(session, epoch, action)
    override fun tryWithChatAttachment(session: ChatSession, epoch: Long?, action: () -> Unit): Boolean =
        runtime.tryWithAttachment(session, epoch, action)
    override fun detachChatSession(session: ChatSession, epoch: Long?, retain: Boolean) {
        if (!runtime.isCurrent(session, epoch)) return
        BackgroundChatGeneration.coordinator.transition(isWanted = { runtime.isCurrent(session, epoch) }) { runtime.detach(session, epoch, retain) }
    }
    override fun detachCompletedChatSession(session: ChatSession, epoch: Long?, retain: Boolean) = runtime.detach(session, epoch, retain)
    override fun unloadChatModel(): Boolean {
        return BackgroundChatGeneration.coordinator.transition { runtime.unload() }
    }
    override fun getResidentModelId(): String? = if (runtime.current()?.isModelLoaded() == true) residentModelId else null
    override fun getActiveSession(): LlmSession? = if (RuntimeOwnership.gate.isApiReserved()) null
        else (runtime.current() as? LlmSession)?.takeIf { it.isModelLoaded() }
    override fun getActiveModelId(): String? = if (RuntimeOwnership.gate.isApiReserved()) apiModelId else getResidentModelId()
    override fun getThinkingEnabled(): Boolean? = getResidentModelId()?.let { ModelConfig.loadConfig(it)?.jinja?.context?.enableThinking != false }
    override fun setThinkingEnabled(enabled: Boolean): Boolean {
        val session = getActiveSession() ?: return false
        return runCatching { session.updateThinking(enabled); true }.getOrDefault(false)
    }
    override fun releaseSession(expected: LlmSession?, chatLeaseEpoch: Long?) {
        if (expected != null) detachChatSession(expected, chatLeaseEpoch, false)
    }

    override fun ensureApiSession(modelId: String, apiEpoch: Long): EnsureSessionResult = BackgroundChatGeneration.coordinator.transition {
        RuntimeOwnership.gate.drainResident(apiEpoch)
        runtime.forgetReleased()
        ResidentModelStatus.clear()
        residentModelId = null
        apiModelId = modelId
        val path = ModelConfig.getDefaultConfigFile(modelId)
            ?: return@transition EnsureSessionResult(false, reason = "MODEL_CONFIG_NOT_FOUND")
        try {
            val session = LlmSession(modelId, "local_api_$apiEpoch", path, null,
                useCustomConfig = false, apiEpoch = apiEpoch)
            session.setKeepHistory(false)
            session.load()
            EnsureSessionResult(true, session, modelId)
        } catch (_: Exception) { EnsureSessionResult(false, reason = "SESSION_INIT_FAILED") }
    }
}
