package com.shieldpericias.sentinel.watchdog

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.securestorage.InactivityLedgerStore
import com.shieldpericias.sentinel.core.time.ClockSource

/** Execução periódica (30 min) do Watchdog + observação do livro-razão. */
class WatchdogScheduler(
    private val context: Context,
    private val checker: WatchdogChecker,
    private val ledgerStore: InactivityLedgerStore,
    private val clock: ClockSource,
) {
    companion object {
        const val ACTION_ALARM = "com.shieldpericias.sentinel.action.WATCHDOG_ALARM"
        private const val REQUEST_CODE = 0x3D06
        const val PERIOD_MS = 30 * 60_000L
    }

    private fun pendingIntent() = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, WatchdogAlarmReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule() {
        context.getSystemService(AlarmManager::class.java)
            ?.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PERIOD_MS, pendingIntent())
    }

    fun cancel() { context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent()) }

    fun onAlarm(): WatchdogReport {
        ledgerStore.observe(clock.snapshot())
        val report = checker.run(notify = true)
        schedule()
        return report
    }
}

class WatchdogAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WatchdogScheduler.ACTION_ALARM) return
        val pending = goAsync()
        Thread {
            try { WatchdogEntryPoint.graph(context.applicationContext)?.watchdogScheduler?.onAlarm() } finally { pending.finish() }
        }.start()
    }
}

/**
 * Boot: observa o livro-razão (tempo ligado), reavalia o antirroubo, agenda o
 * watchdog e inicia o serviço de guarda. Executa tanto em LOCKED_BOOT_COMPLETED
 * (BFU, Device Protected Storage) quanto em BOOT_COMPLETED.
 */
class SentinelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try { WatchdogEntryPoint.graph(context.applicationContext)?.onBoot(action) } finally { pending.finish() }
        }.start()
    }
}

/** Interface mínima que o grafo do app implementa para os componentes deste módulo. */
interface WatchdogGraph {
    val unlockVerifier: UnlockVerifier
    val watchdogScheduler: WatchdogScheduler
    val audit: AuditLog
    fun onBoot(action: String)
    fun onScreenOff() {}
    fun onUserUnlocked() {}
}

object WatchdogEntryPoint {
    @Volatile
    var provider: ((Context) -> WatchdogGraph)? = null

    fun graph(context: Context): WatchdogGraph? = provider?.invoke(context.applicationContext)

    fun logBoot(graph: WatchdogGraph, action: String) = graph.audit.append(AuditTypes.BOOT, action)
}
