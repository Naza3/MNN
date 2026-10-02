// Modified by MNN Chat API contributors, 2026: local-only foreground inference service.
package com.alibaba.mnnllm.api.openai.runtime

import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.llm.LlmSession
import com.alibaba.mnnllm.android.llm.ChatSession

data class EnsureSessionResult(
    val success: Boolean,
    val session: LlmSession? = null,
    val modelId: String? = null,
    val reason: String? = null,
    val chatLeaseEpoch: Long? = null
)

data class EnsureChatSessionResult(val session: ChatSession, val chatLeaseEpoch: Long)

interface LlmRuntimeController {
    fun ensureChatSession(modelId: String, modelName: String, configPath: String?, sessionId: String?,
        historyList: List<ChatDataItem>?, forceReload: Boolean = false,
        isCallerActive: () -> Boolean = { true }): EnsureChatSessionResult =
        throw UnsupportedOperationException("Chat residency is unavailable")
    fun canResumeChatSession(modelId: String, configPath: String?): Boolean = true
    fun isChatAttachmentCurrent(session: ChatSession?, epoch: Long?): Boolean = true
    fun <T> withChatAttachment(session: ChatSession, epoch: Long?, action: () -> T): T = action()
    fun tryWithChatAttachment(session: ChatSession, epoch: Long?, action: () -> Unit): Boolean {
        if (!isChatAttachmentCurrent(session, epoch)) return false
        action(); return true
    }
    fun detachChatSession(session: ChatSession, epoch: Long?, retain: Boolean) { releaseSession(session as? LlmSession, epoch) }
    /** Coordinator cleanup after persistence; must not await its own completion. */
    fun detachCompletedChatSession(session: ChatSession, epoch: Long?, retain: Boolean) = detachChatSession(session, epoch, retain)
    fun unloadChatModel(): Boolean = false
    fun getResidentModelId(): String? = getActiveModelId()

    fun ensureSession(
        modelId: String,
        forceReload: Boolean = false,
        useAppConfig: Boolean = false,
        configPath: String? = null,
        sessionId: String? = null,
        historyList: List<ChatDataItem>? = null,
        deferLoad: Boolean = false
    ): EnsureSessionResult
    fun getActiveSession(): LlmSession?
    fun getActiveModelId(): String?
    fun getThinkingEnabled(): Boolean?
    fun setThinkingEnabled(enabled: Boolean): Boolean
    fun releaseSession(expected: LlmSession? = null, chatLeaseEpoch: Long? = null)
    fun ensureApiSession(modelId: String, apiEpoch: Long): EnsureSessionResult =
        EnsureSessionResult(false, reason = "API_RUNTIME_UNAVAILABLE")
}
