// Modified by MNN Chat API contributors, 2026: retained model with independent UI attachments.
// Created by ruoyi.sjd on 2024/12/25.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.llm

import com.alibaba.mnnllm.android.chat.model.ChatDataItem

interface ChatSession  {
    val debugInfo: String
    val sessionId: String?

    val supportOmni: Boolean
    fun load()
    fun isModelLoaded(): Boolean = false
    /** Cancels only the current UI generation, never the native owner lease. */
    fun cancelGeneration() {}
    /** Runs after the current native call returns. */
    fun detachUi() {}
    fun attachConversation(id: String, history: List<ChatDataItem>?) { reset(); setHistory(history) }

    fun generate(prompt: String, params: Map<String, Any>, progressListener: GenerateProgressListener): HashMap<String, Any>

    fun reset(): String

    fun release()
    fun setKeepHistory(keepHistory: Boolean)
    fun setEnableAudioOutput(enable: Boolean)
    fun getHistory(): List<ChatDataItem>?
    fun setHistory(history:List<ChatDataItem>?)
    fun updateThinking(thinking: Boolean)
}
