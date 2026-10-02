// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.api.openai.service

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.alibaba.mnnllm.api.openai.ui.LocalApiActivity
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalApiPlatformTest {
    @Test fun foregroundServiceIsPrivateSpecialUseAndBackupIsDisabled() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info = context.packageManager.getServiceInfo(ComponentName(context, OpenAIService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
    @Test fun configurationChangesAreRejectedUntilTheRuntimeIsFullyDrained() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val gate = com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnership.gate
        val epoch = checkNotNull(gate.beginApi())
        try {
            assertThrows(IllegalStateException::class.java) { ApiServerConfig.regenerateKey(context) }
            assertThrows(IllegalStateException::class.java) { ApiServerConfig.savePort(context, 9090) }
            gate.requestStop(epoch)
            assertThrows(IllegalStateException::class.java) { ApiServerConfig.resetToDefault(context) }
        } finally { gate.drainResident(epoch); gate.finishStop(epoch) }
    }
    @Test fun disabledBuildNeverInitializesFirebaseForNativeConfiguration() {
        assertFalse(com.alibaba.mnnllm.android.BuildConfig.ENABLE_FIREBASE)
        mockkStatic(com.google.firebase.crashlytics.FirebaseCrashlytics::class)
        try {
            com.alibaba.mnnllm.android.utils.CrashReportContext.reportLlmSetConfig("fake-load", "{\"system_prompt\":\"ephemeral-test-only\"}")
            verify(exactly = 0) { com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance() }
        } finally { unmockkStatic(com.google.firebase.crashlytics.FirebaseCrashlytics::class) }
    }
    @Test fun controlActivityRecreationDoesNotStopTheStartedService() {
        mockkObject(OpenAIService.Companion)
        try {
            every { OpenAIService.getInstance() } returns null
            every { OpenAIService.releaseService(any(), any()) } just Runs
            val controller = Robolectric.buildActivity(LocalApiActivity::class.java).setup()
            controller.recreate()
            controller.pause().stop().destroy()
            verify(exactly = 0) { OpenAIService.releaseService(any(), any()) }
        } finally { unmockkObject(OpenAIService.Companion) }
    }
}
