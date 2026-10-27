package com.shieldpericias.sentinel.antitheft

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Agenda a avaliação periódica (1 h) do Auto-Wipe. Funciona em BFU (receiver
 * directBootAware + Device Protected Storage). Após o boot, reavalia imediatamente.
 */
class AntiTheftScheduler(private val context: Context, private val engine: AutoWipeEngine) {
    companion object {
        const val ACTION_ALARM = "com.shieldpericias.sentinel.action.ANTI_THEFT_ALARM"
        private const val REQUEST_CODE = 0xA7F
        const val PERIOD_MS = 60 * 60_000L
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, AntiTheftAlarmReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PERIOD_MS, pendingIntent())
    }

    fun cancel() { context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent()) }

    fun onAlarm(): AutoWipeEvaluation {
        val result = engine.evaluate(execute = true)
        schedule()
        return result
    }

    fun onBoot(): AutoWipeEvaluation = onAlarm()
}

class AntiTheftAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AntiTheftScheduler.ACTION_ALARM) return
        val pending = goAsync()
        Thread {
            try { AntiTheftEntryPoint.provider?.invoke(context.applicationContext)?.onAlarm() } finally { pending.finish() }
        }.start()
    }
}

object AntiTheftEntryPoint {
    @Volatile
    var provider: ((Context) -> AntiTheftScheduler)? = null
}
