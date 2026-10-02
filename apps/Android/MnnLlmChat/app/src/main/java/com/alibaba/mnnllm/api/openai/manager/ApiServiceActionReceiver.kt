// Modified by MNN Chat API contributors, 2026: no key-bearing URLs or browser launch.
package com.alibaba.mnnllm.api.openai.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alibaba.mnnllm.api.openai.service.OpenAIService

class ApiServiceActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_SERVICE = "com.alibaba.mnnllm.api.openai.STOP_SERVICE"
        const val ACTION_COPY_URL = "com.alibaba.mnnllm.api.openai.COPY_URL"
        const val ACTION_TEST_PAGE = "com.alibaba.mnnllm.api.openai.TEST_PAGE"
        const val EXTRA_URL = "extra_url"
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_SERVICE) OpenAIService.releaseService(context)
    }
}
