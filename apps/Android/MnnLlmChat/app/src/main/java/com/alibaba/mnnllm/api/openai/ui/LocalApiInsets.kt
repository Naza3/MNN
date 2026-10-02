// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.ui

import android.view.View
import androidx.core.view.WindowInsetsCompat

/** Preserve baseline spacing; repeated dispatch and rotation must never accumulate padding. */
internal object LocalApiInsets {
    fun install(view: View, spacingPx: Int) {
        view.setPadding(spacingPx, spacingPx, spacingPx, spacingPx)
        view.setOnApplyWindowInsetsListener { target, platformInsets ->
            val safe = WindowInsetsCompat.toWindowInsetsCompat(platformInsets).getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            target.setPadding(spacingPx + safe.left, spacingPx + safe.top,
                spacingPx + safe.right, spacingPx + safe.bottom)
            platformInsets
        }
        view.requestApplyInsets()
    }
}
