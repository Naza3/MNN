// Modified by MNN Chat API contributors, 2026: explicit local service control surface.
package com.alibaba.mnnllm.api.openai.ui

import android.content.Intent
import android.os.Bundle
import com.alibaba.mnnllm.android.chat.ChatActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ApiSettingsBottomSheetFragment : BottomSheetDialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(requireContext(), LocalApiActivity::class.java).apply {
            putExtra("modelId", (activity as? ChatActivity)?.modelId)
        })
        // The old chat session will be released; returning after Stop opens a fresh Chat from the model list.
        (activity as? ChatActivity)?.finish()
        dismiss()
    }
    companion object { const val TAG = "ApiSettingsBottomSheetFragment" }
}
