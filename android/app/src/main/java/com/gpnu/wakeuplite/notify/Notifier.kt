package com.gpnu.wakeuplite.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.gpnu.wakeuplite.R
import com.gpnu.wakeuplite.data.Change

object Notifier {

    private const val CHANNEL_ID = "timetable_changes"
    private const val CHANNEL_NAME = "调课提醒"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "教务课表发生变化时提醒"
                }
            )
        }
    }

    fun notifyChanges(ctx: Context, changes: List<Change>) {
        ensureChannel(ctx)
        val title = if (changes.size == 1) "课表有 1 处变化" else "课表有 ${changes.size} 处变化"
        val body = changes.take(5).joinToString("\n") { it.text } +
            if (changes.size > 5) "\n…还有 ${changes.size - 5} 处" else ""

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }
}
