// Modified by MNN Chat API contributors, 2026: private foreground status and explicit stop action.
package com.alibaba.mnnllm.api.openai.manager

import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.chat.background.ForegroundNotificationOwner
import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.alibaba.mnnllm.api.openai.ui.LocalApiActivity

class ApiNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var owner: ForegroundNotificationOwner.Token? = null
    @Volatile var generating = false
    fun activate(onRevoked: () -> Unit) { owner = ForegroundNotificationOwner.shared.claim(onRevoked) }
    fun release(removeForeground: () -> Unit) = ForegroundNotificationOwner.shared.release(owner) {
        removeForeground(); manager.cancel(NOTIFICATION_ID)
    }
    companion object { const val NOTIFICATION_ID = ForegroundNotificationOwner.NOTIFICATION_ID; private const val CHANNEL_ID = ForegroundNotificationOwner.CHANNEL_ID }
    init { com.alibaba.mnnllm.android.chat.background.ChatGenerationService.ensureChannel(context) }
    fun buildNotification(contentTitle: String? = null, contentText: String? = null, port: Int = 8080): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getBroadcast(context, 1001,
            Intent(context, ApiServiceActionReceiver::class.java).setAction(ApiServiceActionReceiver.ACTION_STOP_SERVICE), flags)
        val controls = PendingIntent.getActivity(context, 1002, Intent(context, LocalApiActivity::class.java), flags)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (generating) R.drawable.ic_stat_model_generating else R.drawable.ic_stat_model_frame_0)
            .setContentTitle(contentTitle ?: context.getString(R.string.local_api_title))
            .setContentText(contentText ?: "http://127.0.0.1:$port/v1")
            .setContentIntent(controls).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setProgress(0, 0, generating)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.local_api_stop), stop).build()
    }
    fun updateNotification(contentTitle: String, contentText: String, port: Int = 8080) {
        ForegroundNotificationOwner.shared.update(owner) { manager.notify(NOTIFICATION_ID, buildNotification(contentTitle, contentText, port)) }
    }
    fun cancelNotification() { ForegroundNotificationOwner.shared.update(owner) { manager.cancel(NOTIFICATION_ID) } }
}
