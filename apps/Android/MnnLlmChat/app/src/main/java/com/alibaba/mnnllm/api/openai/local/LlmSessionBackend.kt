// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.local

import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession

class LlmSessionBackend(private val session: LlmSession) : LocalInferenceBackend {
    override fun generate(prompt: LocalPrompt, onToken: (String?) -> Boolean): InferenceResult {
        session.reset()
        session.updateMaxNewTokens(prompt.maxTokens)
        val text = StringBuilder()
        var overflow = false
        val result = session.submitFullHistory(prompt.messages.map { android.util.Pair(it.first, it.second) },
            object : GenerateProgressListener {
                override fun onProgress(progress: String?): Boolean {
                    if (progress != null && text.length + progress.length > 1048576) { overflow = true; return true }
                    if (onToken(progress)) return true
                    if (progress != null) text.append(progress)
                    return false
                }
            })
        check(!overflow) { "Generated text exceeds the local response limit" }
        return InferenceResult(text.toString(), (result["prompt_len"] as? Number)?.toInt() ?: 0,
            (result["decode_len"] as? Number)?.toInt() ?: 0)
    }
}
