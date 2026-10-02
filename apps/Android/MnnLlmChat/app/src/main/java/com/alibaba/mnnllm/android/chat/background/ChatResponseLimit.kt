// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

/** Bound UTF-16 accumulation without splitting a surrogate pair at the cutoff. */
class ChatResponseLimit(private val maximum: Int = 1_048_576) {
    var reached: Boolean = false
        private set
    private var accepted = 0
    fun accept(chunk: String?): String? {
        if (chunk == null) return null
        val remaining = (maximum - accepted).coerceAtLeast(0)
        var end = minOf(remaining, chunk.length)
        reached = end >= remaining
        if (end < chunk.length && end > 0 && chunk[end - 1].isHighSurrogate()) end--
        accepted += end
        return chunk.substring(0, end)
    }
}
