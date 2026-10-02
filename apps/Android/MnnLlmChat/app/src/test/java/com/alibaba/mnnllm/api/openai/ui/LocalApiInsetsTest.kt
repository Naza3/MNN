// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.ui

import android.app.Activity
import android.app.Application
import android.widget.ScrollView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalApiInsetsTest {
    private fun view(): ScrollView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return ScrollView(activity).also { activity.setContentView(it); LocalApiInsets.install(it, 16) }
    }
    private fun dispatch(view: ScrollView, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0, ime: Int = 0) {
        view.dispatchApplyWindowInsets(WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(left, top, right, bottom))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(left, top, right, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).build().toWindowInsets()!!)
    }
    @Test fun repeatedInsetsDoNotAccumulateAndZeroRestoresBaseline() {
        val view = view()
        repeat(3) { dispatch(view, top = 52, bottom = 28) }
        assertEquals(68, view.paddingTop); assertEquals(44, view.paddingBottom)
        dispatch(view)
        assertEquals(16, view.paddingTop); assertEquals(16, view.paddingBottom)
    }
    @Test fun rotationCutoutsAndKeyboardKeepEveryEdgeReachable() {
        val view = view()
        dispatch(view, top = 52, bottom = 28)
        dispatch(view, left = 80, right = 24, bottom = 16, ime = 240)
        assertEquals(96, view.paddingLeft); assertEquals(40, view.paddingRight)
        assertEquals(16, view.paddingTop); assertEquals(256, view.paddingBottom)
        dispatch(view, top = 52, bottom = 28)
        assertEquals(16, view.paddingLeft); assertEquals(68, view.paddingTop)
        assertEquals(44, view.paddingBottom)
    }
}
