// Modified by MNN Chat API contributors, 2026: local-only foreground inference service.
package com.alibaba.mnnllm.api.openai.runtime

import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.llm.LlmSession

data class EnsureSessionResult(
    val success: Boolean,
    val session: LlmSession? = null,
    val modelId: String? = null,
    val reason: String? = null,
    val chatLeaseEpoch: Long? = null
)

interface LlmRuntimeController {
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
