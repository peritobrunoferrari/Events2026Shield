package com.shieldpericias.sentinel.watchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Serviço em primeiro plano (tipo specialUse) que mantém o processo vivo para
 * receber ACTION_USER_PRESENT/SCREEN_OFF — broadcasts que só chegam a receivers
 * registrados dinamicamente. Sem ele, o único sinal de desbloqueio seria
 * DeviceAdminReceiver.onPasswordSucceeded (não cobre biometria).
 */
class SentinelGuardService : Service() {

    companion object {
        const val CHANNEL_GUARD = "sentinel.guard"
        const val NOTIFICATION_ID = 0x5E01

        fun start(context: Context) {
            val intent = Intent(context, SentinelGuardService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) { context.stopService(Intent(context, SentinelGuardService::class.java)) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> WatchdogEntryPoint.graph(ctx)?.unlockVerifier?.onUnlockSignal("USER_PRESENT")
                Intent.ACTION_SCREEN_OFF -> WatchdogEntryPoint.graph(ctx)?.onScreenOff()
                Intent.ACTION_USER_UNLOCKED -> WatchdogEntryPoint.graph(ctx)?.onUserUnlocked()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD, getString(R.string.guard_channel_name), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return Notification.Builder(this, CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_sentinel_shield)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(getString(R.string.guard_notification_text))
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
    }
}
