// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.runtime

import android.app.Application
import android.preference.PreferenceManager
import com.alibaba.mnnllm.android.utils.PreferenceUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelResidencyPreferenceTest {
    @Test fun missingUpgradePreferenceDefaultsOnWithoutChangingExistingPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove(PreferenceUtils.KEY_KEEP_MODEL_LOADED).putString("existing_value", "preserved").commit()
        assertTrue(PreferenceUtils.keepModelLoaded(context))
        assertEquals("preserved", prefs.getString("existing_value", null))
    }
    @Test fun explicitOffAndOnArePersisted() {
        val context = RuntimeEnvironment.getApplication()
        PreferenceUtils.setBoolean(context, PreferenceUtils.KEY_KEEP_MODEL_LOADED, false)
        assertFalse(PreferenceUtils.keepModelLoaded(context))
        PreferenceUtils.setBoolean(context, PreferenceUtils.KEY_KEEP_MODEL_LOADED, true)
        assertTrue(PreferenceUtils.keepModelLoaded(context))
    }
}
