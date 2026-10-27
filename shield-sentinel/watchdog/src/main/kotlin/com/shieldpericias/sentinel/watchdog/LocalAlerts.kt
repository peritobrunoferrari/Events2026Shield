package com.shieldpericias.sentinel.watchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context

/** Alertas locais (sem rede) via NotificationManager. */
class LocalAlerts(private val context: Context) {
    companion object {
        const val CHANNEL_ALERTS = "sentinel.alerts"
        private const val ID_LOST = 0x5E02
        private const val ID_INFO = 0x5E03
    }

    private val nm get() = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun contentIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    fun protectionLost(titles: List<String>) {
        ensureChannel()
        val text = titles.joinToString("; ")
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_sentinel_shield)
            .setContentTitle(context.getString(R.string.alert_title_protection_lost))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .apply { contentIntent()?.let { setContentIntent(it) } }
            .setAutoCancel(true)
            .build()
        runCatching { nm?.notify(ID_LOST, n) }
    }

    fun info(title: String, text: String) {
        ensureChannel()
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_sentinel_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .apply { contentIntent()?.let { setContentIntent(it) } }
            .setAutoCancel(true)
            .build()
        runCatching { nm?.notify(ID_INFO, n) }
    }
}
