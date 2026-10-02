// Modified by MNN Chat API contributors, 2026: private foreground status and explicit stop action.
package com.alibaba.mnnllm.api.openai.manager

import com.alibaba.mnnllm.android.R
import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.alibaba.mnnllm.api.openai.ui.LocalApiActivity

class ApiNotificationManager(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    companion object { const val NOTIFICATION_ID = 1001; private const val CHANNEL_ID = "local_api_service" }
    init { manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.local_api_notification_channel), NotificationManager.IMPORTANCE_LOW)) }
    fun buildNotification(contentTitle: String? = null, contentText: String? = null, port: Int = 8080): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getBroadcast(context, 1001,
            Intent(context, ApiServiceActionReceiver::class.java).setAction(ApiServiceActionReceiver.ACTION_STOP_SERVICE), flags)
        val controls = PendingIntent.getActivity(context, 1002, Intent(context, LocalApiActivity::class.java), flags)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.alibaba.mnnllm.android.R.drawable.ic_stat_service)
            .setContentTitle(contentTitle ?: context.getString(R.string.local_api_title))
            .setContentText(contentText ?: "http://127.0.0.1:$port/v1")
            .setContentIntent(controls).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.local_api_stop), stop).build()
    }
    fun updateNotification(contentTitle: String, contentText: String, port: Int = 8080) {
        manager.notify(NOTIFICATION_ID, buildNotification(contentTitle, contentText, port))
    }
    fun cancelNotification() { manager.cancel(NOTIFICATION_ID) }
}
