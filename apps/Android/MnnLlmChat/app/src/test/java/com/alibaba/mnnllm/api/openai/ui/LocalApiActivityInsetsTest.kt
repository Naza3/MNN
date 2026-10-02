// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.ui

import android.app.Application
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalApiActivityInsetsTest {
    @Test fun recreatedControlActivityInstallsInsetsOnItsNewScrollableRoot() {
        val controller = Robolectric.buildActivity(LocalApiActivity::class.java).setup()
        fun checkInsets(activity: LocalApiActivity, left: Int, top: Int, bottom: Int): ScrollView {
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val scroll = content.getChildAt(0) as ScrollView
            val base = (16 * activity.resources.displayMetrics.density).toInt()
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.systemBars(),
                Insets.of(left, top, 0, bottom)).build().toWindowInsets()!!
            repeat(2) { scroll.dispatchApplyWindowInsets(insets) }
            assertEquals(base + left, scroll.paddingLeft)
            assertEquals(base + top, scroll.paddingTop)
            assertEquals(base + bottom, scroll.paddingBottom)
            assertTrue(scroll.isFillViewport)
            return scroll
        }
        val old = checkInsets(controller.get(), 0, 48, 24)
        controller.recreate()
        val next = checkInsets(controller.get(), 64, 0, 20)
        assertNotSame(old, next)
        controller.pause().stop().destroy()
    }
}
