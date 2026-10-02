// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

import com.alibaba.mnnllm.android.llm.ChatSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Published by actual resident-runtime transitions, never reconstructed from saved preferences. */
object ResidentModelStatus {
    data class Loaded(val modelId: String, val modelName: String, val configPath: String,
        val conversationId: String, val native: ChatSession, val cleanupFailed: Boolean = false, val token: String = UUID.randomUUID().toString())
    data class Pending(val modelId: String, val modelName: String, val token: String = UUID.randomUUID().toString())
    private val mutablePending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = mutablePending
    @Volatile private var latestHostToken: String? = null
    @Synchronized fun beginLoading(modelId: String, modelName: String): Pending = Pending(modelId, modelName).also {
        latestHostToken = it.token
        mutablePending.value = it
    }
    fun acceptsHost(token: String?): Boolean = token != null && token == latestHostToken &&
        (mutablePending.value != null || mutable.value != null)
    @Synchronized fun finishLoading(token: String) { if (mutablePending.value?.token == token) mutablePending.value = null }
    private val mutable = MutableStateFlow<Loaded?>(null)
    val state: StateFlow<Loaded?> = mutable
    fun loaded(modelId: String, modelName: String, path: String, native: ChatSession) {
        if (native.isModelLoaded()) mutable.value = Loaded(modelId, modelName, path, native.sessionId ?: "", native)
    }
    fun refreshConversation(native: ChatSession) {
        val old = mutable.value ?: return
        if (old.native === native) mutable.value = old.copy(conversationId = native.sessionId ?: "", token = UUID.randomUUID().toString())
    }
    fun cleanupFailed(native: ChatSession) {
        val old = mutable.value ?: return
        if (old.native === native) mutable.value = old.copy(cleanupFailed = true)
    }
    fun released(native: ChatSession) { if (mutable.value?.native === native) mutable.value = null }
    fun clear() { mutable.value = null }
}
