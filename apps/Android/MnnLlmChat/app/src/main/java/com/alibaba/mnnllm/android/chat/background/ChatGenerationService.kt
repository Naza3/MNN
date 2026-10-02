// Copyright (c) 2026 MNN Chat API contributors. Licensed under Apache-2.0.
package com.alibaba.mnnllm.android.chat.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.chat.ChatActivity
import com.alibaba.mnnllm.api.openai.di.ServiceLocator
import com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** One model indicator: loading -> resident -> generating -> resident -> unloaded. No idle wake lock. */
class ChatGenerationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var owner: ForegroundNotificationOwner.Token? = null
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeJob: String? = null
    private val coordinator get() = BackgroundChatGeneration.coordinator
    private val ownership get() = ForegroundNotificationOwner.shared

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        scope.launch {
            combine(ResidentModelStatus.state, ResidentModelStatus.pending) { _, _ -> Unit }.collect { refresh() }
        }
        scope.launch {
            coordinator.jobs.collectLatest { job ->
                if (job == null) refresh()
                else job.state.distinctUntilChangedBy { it.phase }.collect { refresh() }
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val id = intent?.getStringExtra(EXTRA_JOB_ID)
        if (intent?.action == ACTION_STOP) {
            coordinator.stop(id ?: "")
            refresh()
            if (owner == null) stopSelfResult(latestStartId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_UNLOAD) {
            val token = intent.getStringExtra(EXTRA_RESIDENT_TOKEN)
            if (token != null && ResidentModelStatus.state.value?.token == token && ownership.owns(owner)) {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        coordinator.transition(isWanted = { ResidentModelStatus.state.value?.token == token && coordinator.active() == null && !RuntimeOwnership.gate.isApiReserved() }) {
                            ServiceLocator.getLlmRuntimeController().unloadChatModel()
                        }
                    }
                }
            } else if (owner == null) stopSelfResult(latestStartId)
            return START_NOT_STICKY
        }
        val job = coordinator.find(id)
        val validJob = intent?.action == ACTION_START && job?.state?.value?.phase?.active == true
        val validHost = intent?.action == ACTION_HOST &&
            ResidentModelStatus.acceptsHost(intent.getStringExtra(EXTRA_RESIDENT_TOKEN))
        if ((!validJob && !validHost) || RuntimeOwnership.gate.isApiReserved()) {
            if (owner == null) stopSelfResult(latestStartId)
            return START_NOT_STICKY // Process death cannot reconstruct a job or model from an Intent.
        }
        try {
            if (!ownership.owns(owner)) owner = ownership.claim {
                // DETACH, not REMOVE: the new owner will promote using the same notification ID.
                stopForeground(STOP_FOREGROUND_DETACH)
                releaseWakeLock()
                stopSelf()
            }
            ownership.update(owner) {
                val notification = notification(this)
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIFICATION_ID, notification)
            }
            if (validJob) coordinator.start(job!!.id)
            refresh()
        } catch (_: Exception) {
            if (validJob) { coordinator.failStartup(job!!.id); coordinator.stop(job.id) }
            releaseWakeLock()
            removeForeground()
            stopSelfResult(latestStartId)
        }
        return START_NOT_STICKY
    }

    private fun refresh() {
        try { refreshState() }
        catch (_: Exception) {
            // A denied wake lock or notification operation must not silently kill the observer.
            coordinator.active()?.let { coordinator.stop(it.id) }
            releaseWakeLock()
            runCatching { removeForeground() }
            stopSelfResult(latestStartId)
        }
    }
    private fun refreshState() {
        if (!ownership.owns(owner)) return
        val job = coordinator.active()
        if (job?.started == true && job.id != wakeJob) {
            releaseWakeLock()
            wakeJob = job.id
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:chat-generation").apply {
                    setReferenceCounted(false)
                    acquire(30 * 60 * 1000L)
                }
        } else if (job == null) releaseWakeLock()
        if (job == null && ResidentModelStatus.state.value == null && ResidentModelStatus.pending.value == null) {
            removeForeground()
            stopSelfResult(latestStartId)
        } else ownership.update(owner) {
            if (notificationsVisible(this)) notificationManager().notify(NOTIFICATION_ID, notification(this))
        }
    }
    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun removeForeground() = ownership.release(owner) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager().cancel(NOTIFICATION_ID)
    }
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null; wakeJob = null
    }
    override fun onDestroy() {
        if (ownership.owns(owner)) coordinator.active()?.let { coordinator.stop(it.id) }
        releaseWakeLock()
        removeForeground()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.naza3.mnnchat.START_CHAT_GENERATION"
        const val ACTION_HOST = "io.github.naza3.mnnchat.HOST_CHAT_MODEL"
        const val ACTION_STOP = "io.github.naza3.mnnchat.STOP_CHAT_GENERATION"
        const val ACTION_UNLOAD = "io.github.naza3.mnnchat.UNLOAD_CHAT_MODEL"
        const val EXTRA_JOB_ID = "chatGenerationJobId"
        const val EXTRA_RESIDENT_TOKEN = "residentModelToken"
        const val CHANNEL_ID = ForegroundNotificationOwner.CHANNEL_ID
        const val NOTIFICATION_ID = ForegroundNotificationOwner.NOTIFICATION_ID
        fun startHosting(context: Context, token: String) {
            context.startForegroundService(Intent(context, ChatGenerationService::class.java).apply {
                action = ACTION_HOST; putExtra(EXTRA_RESIDENT_TOKEN, token)
            })
        }
        fun start(context: Context, jobId: String) {
            try {
                context.startForegroundService(Intent(context, ChatGenerationService::class.java).apply {
                    action = ACTION_START; putExtra(EXTRA_JOB_ID, jobId)
                })
            } catch (error: Exception) {
                BackgroundChatGeneration.coordinator.failStartup(jobId)
                throw error
            }
        }
        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val label = context.getString(R.string.chat_background_channel)
            val channel = manager.getNotificationChannel(CHANNEL_ID)
                ?: NotificationChannel(CHANNEL_ID, label, NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            // Update descriptive text only. Never raise/reset an existing channel's importance,
            // sound, visibility or user block, and never create an alternate channel to bypass it.
            channel.name = label
            channel.description = context.getString(R.string.chat_background_channel_description)
            manager.createNotificationChannel(channel)
        }
        fun notificationsVisible(context: Context): Boolean {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            return (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        internal fun returnIntent(context: Context, modelId: String, modelName: String, config: String?,
            conversation: String?, identity: String) = Intent(context, ChatActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("modelId", modelId); putExtra("modelName", modelName)
            putExtra("configFilePath", config); putExtra("chatSessionId", conversation)
            data = Uri.parse("mnnchat://chat/$identity")
        }
        internal fun notification(context: Context): Notification {
            val job = BackgroundChatGeneration.coordinator.active()
            val loaded = ResidentModelStatus.state.value
            val pending = ResidentModelStatus.pending.value
            val phase = job?.state?.value?.phase
            val busy = job != null
            val title = context.getString(when {
                phase == ChatGenerationCoordinator.Phase.STOPPING -> R.string.chat_background_stopping
                busy -> R.string.chat_background_generating
                loaded?.cleanupFailed == true -> R.string.chat_model_cleanup_failed
                loaded != null -> R.string.chat_model_loaded
                else -> R.string.chat_model_loading
            })
            val modelName = job?.request?.modelName ?: loaded?.modelName ?: pending?.modelName ?: ""
            val modelId = job?.request?.modelId ?: loaded?.modelId ?: pending?.modelId ?: ""
            val identity = job?.id ?: loaded?.token ?: pending?.token ?: "closed"
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(context, 0, returnIntent(context, modelId, modelName,
                job?.request?.configPath ?: loaded?.configPath,
                job?.request?.conversationId ?: loaded?.conversationId, identity), flags)
            val public = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_service)
                .setContentTitle(context.getString(R.string.app_name)).setContentText(title).build()
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(if (busy) R.drawable.ic_stat_model_generating else R.drawable.ic_stat_model_frame_0)
                .setContentTitle(title).setContentText(modelName).setContentIntent(open)
                .setOnlyAlertOnce(true).setSilent(true).setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public).setProgress(0, 0, busy)
            if (job != null) {
                val stop = Intent(context, ChatGenerationService::class.java).apply {
                    action = ACTION_STOP; putExtra(EXTRA_JOB_ID, job.id); data = Uri.parse("mnnchat://stop/${job.id}")
                }
                builder.addAction(0, context.getString(R.string.chat_background_stop), PendingIntent.getService(context, 0, stop, flags))
            } else if (loaded != null && !loaded.cleanupFailed) {
                val unload = Intent(context, ChatGenerationService::class.java).apply {
                    action = ACTION_UNLOAD; putExtra(EXTRA_RESIDENT_TOKEN, loaded.token)
                    data = Uri.parse("mnnchat://unload/${loaded.token}")
                }
                builder.addAction(0, context.getString(R.string.unload_model), PendingIntent.getService(context, 0, unload, flags))
            }
            return builder.build()
        }
    }
}
