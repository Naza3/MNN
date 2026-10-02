// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.chat.ChatActivity
import com.alibaba.mnnllm.android.llm.ChatSession
import com.alibaba.mnnllm.android.chat.model.ChatDataItem
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.api.openai.di.ServiceLocator
import com.alibaba.mnnllm.api.openai.runtime.LlmRuntimeController
import com.alibaba.mnnllm.api.openai.runtime.EnsureSessionResult
import com.alibaba.mnnllm.android.llm.LlmSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatGenerationServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun native(): ChatSession = object : ChatSession {
        override val debugInfo = "fake"
        override val sessionId = "conversation-only"
        override val supportOmni = false
        override fun load() {}
        override fun isModelLoaded() = true
        override fun generate(prompt: String, params: Map<String, Any>, progressListener: GenerateProgressListener) = hashMapOf<String, Any>()
        override fun reset() = sessionId
        override fun release() {}
        override fun setKeepHistory(keepHistory: Boolean) {}
        override fun setEnableAudioOutput(enable: Boolean) {}
        override fun getHistory(): List<ChatDataItem>? = null
        override fun setHistory(history: List<ChatDataItem>?) {}
        override fun updateThinking(thinking: Boolean) {}
    }
    @After fun clear() {
        ResidentModelStatus.pending.value?.let { ResidentModelStatus.finishLoading(it.token) }
        ResidentModelStatus.clear()
        ServiceLocator.reset()
    }
    @Test fun manifestDeclaresPrivateSpecialUseHostAndWakeLock() {
        val info = context.packageManager.getServiceInfo(android.content.ComponentName(context, ChatGenerationService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, info.foregroundServiceType)
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(android.Manifest.permission.WAKE_LOCK, context.packageName))
    }
    @Test fun loadedAfterFastCompletionIsPromotedWithStaticIndicatorAndNoWakeLock() {
        val host = ResidentModelStatus.beginLoading("model", "Visible model")
        ResidentModelStatus.loaded("model", "Visible model", "/config", native())
        ResidentModelStatus.finishLoading(host.token)
        val controller = Robolectric.buildService(ChatGenerationService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(Intent(context, ChatGenerationService::class.java).apply {
            action = ChatGenerationService.ACTION_HOST
            putExtra(ChatGenerationService.EXTRA_RESIDENT_TOKEN, host.token)
        }, 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals(R.drawable.ic_stat_model_frame_0, notification.smallIcon.resId)
        val lock = service.javaClass.getDeclaredField("wakeLock").apply { isAccessible = true }.get(service) as? PowerManager.WakeLock
        assertTrue(lock == null || !lock.isHeld)
        controller.destroy()
    }
    @Test fun processLocalStateIsRequiredAndStaleStartDoesNotReplayGeneration() {
        val controller = Robolectric.buildService(ChatGenerationService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(context, ChatGenerationService::class.java).apply {
            action = ChatGenerationService.ACTION_START; putExtra(ChatGenerationService.EXTRA_JOB_ID, "dead-process-job")
        }, 0, 8))
        assertNull(shadowOf(service).lastForegroundNotification)
        assertNull(BackgroundChatGeneration.coordinator.active())
        controller.destroy()
    }
    @Test fun notificationReturnTargetsOnlySavedModelAndConversation() {
        val intent = ChatGenerationService.returnIntent(context, "model", "Visible model", "/config", "saved-conversation", "identity")
        assertEquals(ChatActivity::class.java.name, intent.component!!.className)
        assertEquals("saved-conversation", intent.getStringExtra("chatSessionId"))
        assertEquals("model", intent.getStringExtra("modelId"))
        assertFalse(intent.extras!!.containsKey("prompt")); assertFalse(intent.extras!!.containsKey("response"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }
    @Test fun loadedNotificationHasExplicitImmutableUnloadAndPrivatePublicVersion() {
        ResidentModelStatus.loaded("model", "Visible model", "/config", native())
        ChatGenerationService.ensureChannel(context)
        val notification = ChatGenerationService.notification(context)
        assertEquals("Visible model", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertNotNull(notification.publicVersion)
        assertNotEquals("Visible model", notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        val action = notification.actions.single().actionIntent
        val intent = shadowOf(action).savedIntent
        assertEquals(ChatGenerationService.ACTION_UNLOAD, intent.action)
        assertEquals(ChatGenerationService::class.java.name, intent.component!!.className)
        assertTrue(action.isImmutable)
        assertEquals(ResidentModelStatus.state.value!!.token, intent.getStringExtra(ChatGenerationService.EXTRA_RESIDENT_TOKEN))
    }
    @Test fun disabledNotificationChannelIsReportedWithoutChangingSystemSettings() {
        ChatGenerationService.ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(ChatGenerationService.CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(channel)
        assertFalse(ChatGenerationService.notificationsVisible(context))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(ChatGenerationService.CHANNEL_ID).importance)
    }
    @Test fun actualServiceKeepsJobAndWakeLockUntilStopDrainsThenReturnsToIdleIcon() {
        com.alibaba.mls.api.ApplicationProvider.set(context)
        val entered = CountDownLatch(1); val returned = CountDownLatch(1)
        val base = native()
        val native = object : ChatSession by base {
            override fun generate(prompt: String, params: Map<String, Any>, progressListener: GenerateProgressListener): HashMap<String, Any> {
                progressListener.onProgress("partial")
                entered.countDown(); check(returned.await(5, TimeUnit.SECONDS))
                progressListener.onProgress("complete")
                return hashMapOf("decode_len" to 2L)
            }
        }
        var unloads = 0
        ServiceLocator.setLlmRuntimeController(object : LlmRuntimeController {
            override fun isChatAttachmentCurrent(session: ChatSession?, epoch: Long?) = session === native && epoch == 1L
            override fun ensureSession(modelId: String, forceReload: Boolean, useAppConfig: Boolean, configPath: String?, sessionId: String?, historyList: List<ChatDataItem>?, deferLoad: Boolean) = EnsureSessionResult(false)
            override fun getActiveSession(): LlmSession? = null
            override fun getActiveModelId() = "model"
            override fun getThinkingEnabled(): Boolean? = null
            override fun setThinkingEnabled(enabled: Boolean) = false
            override fun releaseSession(expected: LlmSession?, chatLeaseEpoch: Long?) {}
            override fun unloadChatModel(): Boolean { unloads++; return true }
        })
        ResidentModelStatus.loaded("model", "Visible model", "/config", native)
        val oldIdleToken = ResidentModelStatus.state.value!!.token
        val core = BackgroundChatGeneration.coordinator
        val attachment = core.admit(ChatGenerationCoordinator.Request("conversation-only", "model", "Visible model", "/config", "private prompt", "time"),
            BackgroundChatGeneration.Lease(native, 1L, "conversation-only"))
        val controller = Robolectric.buildService(ChatGenerationService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(Intent(context, ChatGenerationService::class.java).apply {
                action = ChatGenerationService.ACTION_START
                putExtra(ChatGenerationService.EXTRA_JOB_ID, attachment.job.id)
            }, 0, 1)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val busy = shadowOf(notifications).getNotification(ChatGenerationService.NOTIFICATION_ID)
            assertEquals(R.drawable.ic_stat_model_generating, busy.smallIcon.resId)
            assertTrue(busy.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertFalse(busy.extras.toString().contains("private prompt"))
            assertFalse(busy.extras.toString().contains("partial"))
            val wake = service.javaClass.getDeclaredField("wakeLock").apply { isAccessible = true }
            assertTrue((wake.get(service) as PowerManager.WakeLock).isHeld)
            service.onStartCommand(Intent(context, ChatGenerationService::class.java).apply {
                action = ChatGenerationService.ACTION_UNLOAD
                putExtra(ChatGenerationService.EXTRA_RESIDENT_TOKEN, oldIdleToken)
            }, 0, 2)
            service.onStartCommand(Intent(context, ChatGenerationService::class.java).apply {
                action = ChatGenerationService.ACTION_STOP
                putExtra(ChatGenerationService.EXTRA_JOB_ID, "older-job")
            }, 0, 3)
            assertEquals(0, unloads); assertFalse(attachment.job.cancelled.get())
            service.onStartCommand(Intent(context, ChatGenerationService::class.java).apply {
                action = ChatGenerationService.ACTION_STOP
                putExtra(ChatGenerationService.EXTRA_JOB_ID, attachment.job.id)
            }, 0, 4)
            assertFalse(attachment.job.completion.isCompleted)
            returned.countDown()
            runBlocking { withTimeout(5000) { attachment.job.completion.await() } }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val idle = shadowOf(notifications).getNotification(ChatGenerationService.NOTIFICATION_ID)
            assertEquals(R.drawable.ic_stat_model_frame_0, idle.smallIcon.resId)
            assertFalse(idle.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertNull(wake.get(service)); assertTrue(attachment.job.state.value.saved)
        } finally {
            returned.countDown(); core.cancelAndDrain(); core.retire(attachment); controller.destroy()
        }
    }

    @Test fun previouslyBlockedApiChannelRemainsSharedAndBlockedAcrossBothManagers() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(android.app.NotificationChannel("local_api_service", "Old API label", NotificationManager.IMPORTANCE_NONE))
        ChatGenerationService.ensureChannel(context)
        val api = com.alibaba.mnnllm.api.openai.manager.ApiNotificationManager(context)
        val chatNotification = ChatGenerationService.notification(context)
        val apiNotification = api.buildNotification()
        assertEquals("local_api_service", chatNotification.channelId)
        assertEquals(chatNotification.channelId, apiNotification.channelId)
        assertEquals(ForegroundNotificationOwner.CHANNEL_ID, chatNotification.channelId)
        val shared = manager.getNotificationChannel(ForegroundNotificationOwner.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_NONE, shared.importance)
        assertEquals(context.getString(R.string.chat_background_channel), shared.name)
        assertFalse(ChatGenerationService.notificationsVisible(context))
        assertNull(manager.getNotificationChannel("chat_model_runtime"))
        // Reinitialization must not use a fresh default-low channel to restore notification access.
        ChatGenerationService.ensureChannel(context)
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(ForegroundNotificationOwner.CHANNEL_ID).importance)
    }

    @Test fun existingNonDefaultImportanceIsNotLoweredByChatOrApiInitialization() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(android.app.NotificationChannel("local_api_service", "Old API label", NotificationManager.IMPORTANCE_HIGH))
        ChatGenerationService.ensureChannel(context)
        com.alibaba.mnnllm.api.openai.manager.ApiNotificationManager(context)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(ForegroundNotificationOwner.CHANNEL_ID).importance)
    }

}
