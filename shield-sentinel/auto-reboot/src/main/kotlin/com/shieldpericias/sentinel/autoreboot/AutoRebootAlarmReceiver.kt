package com.shieldpericias.sentinel.autoreboot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receptor do alarme de Auto-Reboot. A instância do [RebootScheduler] é obtida
 * via [AutoRebootEntryPoint], configurado pelo grafo do aplicativo.
 */
class AutoRebootAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RebootScheduler.ACTION_ALARM) return
        val pending = goAsync()
        Thread {
            try {
                AutoRebootEntryPoint.scheduler(context.applicationContext)?.onAlarm()
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Ponto de acesso configurado pelo app (evita dependência do módulo no grafo do app). */
object AutoRebootEntryPoint {
    @Volatile
    var provider: ((Context) -> RebootScheduler)? = null

    fun scheduler(context: Context): RebootScheduler? = provider?.invoke(context)
}
