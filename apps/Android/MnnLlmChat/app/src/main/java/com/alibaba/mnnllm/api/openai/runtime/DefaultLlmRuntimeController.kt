// Modified by MNN Chat API contributors, 2026: exclusive API runtime ownership.
package com.alibaba.mnnllm.api.openai.runtime

import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.llm.ChatService
import com.alibaba.mnnllm.android.llm.LlmSession
import com.alibaba.mnnllm.android.model.ModelUtils
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import timber.log.Timber

object DefaultLlmRuntimeController : LlmRuntimeController {
    private val lock = Any()
    private val chatAttachments = ChatAttachmentGuard<LlmSession>()
    @Volatile private var activeModelId: String? = null
    private var activeSession: LlmSession? = null
    private var activeUseAppConfig: Boolean = false

    override fun ensureSession(
        modelId: String,
        forceReload: Boolean,
        useAppConfig: Boolean,
        configPath: String?,
        sessionId: String?,
        historyList: List<ChatDataItem>?,
        deferLoad: Boolean
    ): EnsureSessionResult {
        if (RuntimeOwnership.gate.isApiReserved()) return EnsureSessionResult(false, reason = "API_OWNS_RUNTIME")
        synchronized(lock) {
            if (RuntimeOwnership.gate.isApiReserved()) return EnsureSessionResult(false, reason = "API_OWNS_RUNTIME")
            val currentSession = activeSession
            if (
                currentSession != null &&
                RuntimeSessionReusePolicy.shouldReuse(
                    forceReload = forceReload,
                    activeModelId = activeModelId,
                    requestedModelId = modelId,
                    isSessionLoaded = currentSession.isModelLoaded(),
                    activeUseAppConfig = activeUseAppConfig,
                    requestedUseAppConfig = useAppConfig
                )
            ) {
                return EnsureSessionResult(
                    success = true,
                    session = currentSession,
                    modelId = modelId,
                    chatLeaseEpoch = chatAttachments.attach(currentSession)
                )
            }

            if (currentSession != null) {
                releaseSessionLocked()
            }

            val resolvedConfigPath = configPath ?: if (useAppConfig) {
                ModelUtils.getConfigPathForModel(modelId)
            } else {
                ModelConfig.getDefaultConfigFile(modelId)
            } ?: return EnsureSessionResult(
                success = false,
                modelId = modelId,
                reason = "MODEL_CONFIG_NOT_FOUND"
            )

            val modelName = ModelUtils.getModelName(modelId) ?: modelId
            val resolvedSessionId = sessionId ?: "service_runtime_${System.currentTimeMillis()}"

            return runCatching {
                val chatSession = ChatService.provide().createSession(
                    modelId = modelId,
                    modelName = modelName,
                    sessionIdParam = resolvedSessionId,
                    historyList = historyList,
                    configPath = resolvedConfigPath,
                    useNewConfig = !useAppConfig,
                    useCustomConfig = useAppConfig
                )

                val llmSession = chatSession as? LlmSession
                    ?: return EnsureSessionResult(
                        success = false,
                        modelId = modelId,
                        reason = "SESSION_NOT_LLM"
                    )

                activeSession = llmSession
                activeModelId = modelId
                llmSession.setKeepHistory(true)
                if (!deferLoad) {
                    llmSession.load()
                }
                activeSession = llmSession
                activeModelId = modelId
                activeUseAppConfig = useAppConfig
                EnsureSessionResult(
                    success = true,
                    session = llmSession,
                    modelId = modelId,
                    chatLeaseEpoch = chatAttachments.attach(llmSession)
                )
            }.getOrElse { error ->
                Timber.w(error, "ensureSession failed for modelId=%s", modelId)
                releaseSessionLocked()
                EnsureSessionResult(
                    success = false,
                    modelId = modelId,
                    reason = "SESSION_INIT_FAILED"
                )
            }
        }
    }

    override fun getActiveSession(): LlmSession? {
        if (RuntimeOwnership.gate.isApiReserved()) return null
        synchronized(lock) {
            // API uses its private ensureApiSession result, never the UI session provider.
            if (RuntimeOwnership.gate.isApiReserved()) return null
            val session = activeSession ?: return null
            return session.takeIf {
                RuntimeSessionReusePolicy.shouldExposeActiveSession(it.isModelLoaded())
            }
        }
    }

    override fun getActiveModelId(): String? {
        synchronized(lock) {
            return activeModelId
        }
    }

    override fun getThinkingEnabled(): Boolean? {
        synchronized(lock) {
            val modelId = activeModelId ?: return null
            val config = ModelConfig.loadConfig(modelId) ?: return null
            return config.jinja?.context?.enableThinking != false
        }
    }

    override fun setThinkingEnabled(enabled: Boolean): Boolean {
        if (RuntimeOwnership.gate.isApiReserved()) return false
        synchronized(lock) {
            if (RuntimeOwnership.gate.isApiReserved()) return false
            val session = getActiveSession() ?: return false
            return runCatching {
                session.updateThinking(enabled)
                true
            }.getOrElse { error ->
                Timber.w(error, "setThinkingEnabled failed enabled=%s", enabled)
                false
            }
        }
    }

    override fun releaseSession(expected: LlmSession?, chatLeaseEpoch: Long?) {
        if (RuntimeOwnership.gate.isApiReserved()) return
        synchronized(lock) {
            // UI callbacks must identify their own session; a late Activity cannot release API/new chat.
            if (activeSession !== expected || !chatAttachments.isCurrent(expected, chatLeaseEpoch)) return
            if (RuntimeOwnership.gate.isApiReserved()) return
            releaseSessionLocked()
        }
    }

    override fun ensureApiSession(modelId: String, apiEpoch: Long): EnsureSessionResult {
        synchronized(lock) {
            chatAttachments.invalidate()
            RuntimeOwnership.gate.drainResident(apiEpoch)
            activeSession = null
            activeModelId = null
            val path = ModelConfig.getDefaultConfigFile(modelId)
                ?: return EnsureSessionResult(false, reason = "MODEL_CONFIG_NOT_FOUND")
            return try {
                val session = LlmSession(modelId, "local_api_$apiEpoch", path, null,
                    useCustomConfig = false, apiEpoch = apiEpoch)
                activeSession = session // retain even if load fails, so cleanup can release its lease
                activeModelId = modelId
                activeUseAppConfig = false
                session.setKeepHistory(false)
                session.load()
                EnsureSessionResult(true, session, modelId)
            } catch (e: Exception) {
                EnsureSessionResult(false, reason = "SESSION_INIT_FAILED")
            }
        }
    }

    private fun releaseSessionLocked() {
        chatAttachments.invalidate()
        activeSession?.requestCancellation()
        activeSession?.release()
        activeSession = null
        activeModelId = null
        activeUseAppConfig = false
    }
}
