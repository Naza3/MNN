// Modified by MNN Chat API contributors, 2026: foreground service independent of Activity lifetime.
package com.alibaba.mnnllm.api.openai.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.alibaba.mnnllm.api.openai.manager.ApiNotificationManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class OpenAIService : Service() {
    private lateinit var coordinator: ApiServiceCoordinator
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopping = AtomicBoolean(false)
    private var currentModelId: String? = null
    private var startRequestCount = 0
    companion object {
        const val ACTION_START = "io.github.naza3.mnnchat.START_LOCAL_API"
        const val ACTION_STOP = "io.github.naza3.mnnchat.STOP_LOCAL_API"
        @Volatile private var activeInstance: OpenAIService? = null
        fun getInstance(): OpenAIService? = activeInstance
        fun startService(context: Context, modelId: String? = null) {
            require(!modelId.isNullOrBlank()) { "Select a downloaded model in Chat first" }
            context.startForegroundService(Intent(context, OpenAIService::class.java).apply {
                action = ACTION_START; putExtra("modelId", modelId)
            })
        }
        fun releaseService(context: Context, force: Boolean = false) {
            // Never stopService first: onDestroy is not a safe native cleanup execution scope.
            activeInstance?.requestStop()
        }
    }
    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        coordinator = ApiServiceCoordinator(this)
        coordinator.initialize()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { requestStop(); return START_NOT_STICKY }
        val model = intent?.getStringExtra("modelId")
        if (intent?.action != ACTION_START || model.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        startRequestCount++
        // Repeated starts never replace the model or allocate another runtime.
        if (coordinator.epoch != null || stopping.get()) return START_NOT_STICKY
        try {
            val notification = coordinator.getNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ApiNotificationManager.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else { startForeground(ApiNotificationManager.NOTIFICATION_ID, notification) }
            if (!coordinator.reserve()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            currentModelId = model
            scope.launch { if (!coordinator.startServer(model)) requestStop() }
        } catch (e: Exception) { requestStop() }
        return START_NOT_STICKY
    }
    fun requestStop() {
        if (!stopping.compareAndSet(false, true)) return
        coordinator.requestStop()
        scope.launch {
            val cleaned = coordinator.cleanup()
            if (cleaned) {
                withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                if (activeInstance === this@OpenAIService) activeInstance = null
                scope.cancel()
            }
            // Failed cleanup deliberately keeps the reservation and foreground warning.
        }
    }
    override fun onDestroy() {
        // Android may destroy the Service unexpectedly. Cleanup remains owned by its independent scope.
        requestStop()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    inner class LocalBinder : Binder() { fun getService(): OpenAIService = this@OpenAIService }
    fun updateNotification(contentTitle: String, contentText: String) = coordinator.updateNotification(contentTitle, contentText)
    fun getServerPort(): Int? = coordinator.getServerPort()
    fun isServerRunning() = coordinator.isServerRunning
    fun getCurrentModelId(): String? = currentModelId
    fun getBootstrapCount() = coordinator.getBootstrapCount()
    fun getStartRequestCount() = startRequestCount
}
