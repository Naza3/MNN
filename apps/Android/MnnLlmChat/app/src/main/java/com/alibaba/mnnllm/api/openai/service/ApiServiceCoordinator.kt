// Modified by MNN Chat API contributors, 2026: service-owned, exclusive runtime lifecycle.
package com.alibaba.mnnllm.api.openai.service

import android.content.Context
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.api.openai.di.ServiceLocator
import com.alibaba.mnnllm.api.openai.local.*
import com.alibaba.mnnllm.api.openai.manager.ApiNotificationManager
import com.alibaba.mnnllm.api.openai.manager.CurrentModelManager
import com.alibaba.mnnllm.api.openai.manager.ServerEventManager
import com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnership
import com.alibaba.mnnllm.api.openai.runtime.RuntimeOwnerGate
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty

class ApiServiceCoordinator(private val context: Context) {
    private val notifications = ApiNotificationManager(context)
    private var modelId: String? = null
    private val lifecycle = LocalApiLifecycle(RuntimeOwnership.gate, load = { epoch ->
        val result = ServiceLocator.getLlmRuntimeController().ensureApiSession(checkNotNull(modelId), epoch)
        check(result.success && result.session != null) { "Cannot load the selected model" }
        val backend = LlmSessionBackend(result.session!!)
        LocalInferenceBackend { prompt, onToken ->
            setGenerationActive(true)
            try { backend.generate(prompt, onToken) }
            finally { setGenerationActive(false) }
        }
    }, transportFactory = { worker ->
        val port = ApiServerConfig.getPort(context)
        ApiServerConfig.validateEndpoint(ApiServerConfig.LOOPBACK, port)
        ApiServerConfig.initializeConfig(context)
        val engine = embeddedServer(Netty, configure = {
            connector { host = ApiServerConfig.LOOPBACK; this.port = port }
            enableHttp2 = false // This local API's verified disconnect contract is HTTP/1.1 TCP closure.
        }) {
            localApiModule(worker, checkNotNull(modelId), { ApiServerConfig.getApiKey(context) },
                { RuntimeOwnership.gate.state == RuntimeOwnerGate.State.READY_API })
        }
        object : LocalApiTransport {
            override fun start() { engine.start(wait = false) }
            override suspend fun stop() { engine.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1000) }
        }
    }, onState = { state ->
        val events = ServerEventManager.getInstance()
        when (state) {
            RuntimeOwnerGate.State.CHAT -> { CurrentModelManager.clearCurrentModelId(); events.handleApplicationStopped() }
            RuntimeOwnerGate.State.STARTING_API -> {
                events.handleApplicationStarting(ApiServerConfig.LOOPBACK, ApiServerConfig.getPort(context))
                updateNotification(context.getString(R.string.local_api_notification_starting), context.getString(R.string.local_api_wait_load))
            }
            RuntimeOwnerGate.State.READY_API -> {
                modelId?.let(CurrentModelManager::setCurrentModelId)
                events.handleServerReady(ApiServerConfig.LOOPBACK, ApiServerConfig.getPort(context))
                updateNotification(context.getString(R.string.local_api_ready), context.getString(R.string.local_api_notification_ready, ApiServerConfig.getPort(context)))
            }
            RuntimeOwnerGate.State.STOPPING_API -> { events.handleApplicationStopping(); updateNotification(context.getString(R.string.local_api_stopping), context.getString(R.string.local_api_wait_native)) }
            RuntimeOwnerGate.State.CLEANUP_FAILED -> updateNotification(context.getString(R.string.local_api_cleanup_failed), context.getString(R.string.local_api_restart_app))
        }
    })
    val epoch: Long? get() = lifecycle.epoch
    val isServerRunning: Boolean get() = lifecycle.isReady()
    fun initialize() = true
    fun reserve(): Boolean = lifecycle.reserve()
    suspend fun startServer(modelId: String): Boolean {
        this.modelId = modelId
        com.alibaba.mnnllm.android.chat.background.BackgroundChatGeneration.coordinator.cancelAndDrain()
        return lifecycle.start()
    }
    fun requestStop() = lifecycle.requestStop()
    suspend fun cleanup(): Boolean {
        com.alibaba.mnnllm.android.chat.background.BackgroundChatGeneration.coordinator.cancelAndDrain()
        return lifecycle.cleanup()
    }
    fun activateNotificationOwnership(onRevoked: () -> Unit) = notifications.activate(onRevoked)
    fun releaseNotificationOwnership(removeForeground: () -> Unit) = notifications.release(removeForeground)
    private fun setGenerationActive(active: Boolean) = runCatching {
        notifications.generating = active
        if (RuntimeOwnership.gate.state == RuntimeOwnerGate.State.READY_API) {
            updateNotification(context.getString(if (active) R.string.chat_background_generating else R.string.local_api_ready),
                modelId ?: context.getString(R.string.local_api_title))
        }
    }
    fun getBootstrapCount() = lifecycle.bootstrapCount
    fun getServerPort(): Int? = if (epoch != null) ApiServerConfig.getPort(context) else null
    fun getNotification() = notifications.buildNotification(context.getString(R.string.local_api_notification_starting), context.getString(R.string.local_api_wait_load), ApiServerConfig.getPort(context))
    fun updateNotification(title: String, content: String, port: Int = ApiServerConfig.getPort(context)) = notifications.updateNotification(title, content, port)
}
