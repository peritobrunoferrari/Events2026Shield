package com.shieldpericias.sentinel.autoreboot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.core.FeatureState
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.core.inactivity.RebootPlanner
import com.shieldpericias.sentinel.core.time.ClockSource
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade
import com.shieldpericias.sentinel.securestorage.InactivityLedgerStore

/**
 * Auto-Reboot: reinicia o aparelho após [AutoRebootConfig.intervalMs] sem desbloqueio
 * verificado. Usa AlarmManager (exato quando permitido; inexato com janela caso contrário).
 *
 * Limitações documentadas: o Android pode adiar alarmes inexatos em Doze; alarmes
 * não sobrevivem ao reboot (desnecessário: após reiniciar o aparelho já está em BFU);
 * `reboot()` falha durante chamadas telefônicas (reagendado em 10 min).
 */
class RebootScheduler(
    private val context: Context,
    private val configStore: AutoRebootConfigStore,
    private val ledgerStore: InactivityLedgerStore,
    private val clock: ClockSource,
    private val facade: DevicePolicyFacade,
    private val executor: RebootExecutor,
    private val audit: AuditLog,
) {
    companion object {
        const val ACTION_ALARM = "com.shieldpericias.sentinel.action.AUTO_REBOOT_ALARM"
        private const val REQUEST_CODE = 0x5EB0
        private const val RETRY_MS = 10 * 60_000L
    }

    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, AutoRebootAlarmReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Recalcula e (re)arma o alarme conforme o livro-razão. Chame após desbloqueio verificado e no boot. */
    fun rearm(): RebootPlanner.Plan {
        val cfg = configStore.load()
        val plan = RebootPlanner.plan(ledgerStore.load(), clock.snapshot(), cfg.intervalMs)
        if (!cfg.enabled) { cancel(); return plan }
        when (plan) {
            is RebootPlanner.Plan.Armed -> {
                scheduleAtElapsed(plan.atElapsedMs)
                audit.append(AuditTypes.AUTO_REBOOT_ARMED, "em ${plan.remainingMs / 60_000} min")
            }
            RebootPlanner.Plan.Due -> onAlarm()
            RebootPlanner.Plan.NotArmedBfu, RebootPlanner.Plan.NoBaseline -> cancel()
        }
        return plan
    }

    private fun scheduleAtElapsed(atElapsedMs: Long) {
        val am = alarmManager ?: return
        val pi = pendingIntent()
        val triggerAt = maxOf(atElapsedMs, SystemClock.elapsedRealtime() + 1_000)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        } else {
            am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, 10 * 60_000L, pi)
        }
    }

    fun cancel() { alarmManager?.cancel(pendingIntent()) }

    /** Disparo do alarme: reavalia com relógio fresco antes de agir. */
    fun onAlarm(): PolicyResult {
        val cfg = configStore.load()
        if (!cfg.enabled) return PolicyResult.Unsupported("Auto-Reboot desativado")
        val now = clock.snapshot()
        val state = ledgerStore.observe(now)
        val plan = RebootPlanner.plan(state, now, cfg.intervalMs)
        if (plan !is RebootPlanner.Plan.Due) {
            if (plan is RebootPlanner.Plan.Armed) scheduleAtElapsed(plan.atElapsedMs)
            audit.append(AuditTypes.AUTO_REBOOT_SKIPPED, plan.javaClass.simpleName)
            return PolicyResult.Unsupported("ainda não vencido: $plan")
        }
        if (!facade.mode().isDeviceOwner) {
            audit.append(AuditTypes.AUTO_REBOOT_SKIPPED, "sem Device Owner")
            return PolicyResult.Unsupported("reboot() exige Device Owner")
        }
        if (cfg.dryRun) {
            audit.append(AuditTypes.AUTO_REBOOT_SKIPPED, "modo de ensaio (dry-run)")
            return PolicyResult.Applied
        }
        // Persistir observação antes de reiniciar (o tempo ligado conta para o Auto-Wipe).
        ledgerStore.observe(clock.snapshot())
        audit.append(AuditTypes.AUTO_REBOOT_EXECUTED, "intervalo ${cfg.intervalMs / 60_000} min")
        val r = executor.reboot("auto-reboot")
        if (!r.ok) {
            // Ex.: IllegalStateException durante chamada → tenta novamente em 10 min.
            scheduleAtElapsed(SystemClock.elapsedRealtime() + RETRY_MS)
            audit.append(AuditTypes.AUTO_REBOOT_SKIPPED, "falha: ${r.javaClass.simpleName}; nova tentativa em 10 min")
        }
        return r
    }

    fun check(): PolicyCheck {
        val cfg = configStore.load()
        val plan = RebootPlanner.plan(ledgerStore.load(), clock.snapshot(), cfg.intervalMs)
        val state = when {
            !facade.mode().isDeviceOwner -> FeatureState.Unavailable("Exige Device Owner")
            !cfg.enabled -> FeatureState.Inactive("Desativado")
            plan is RebootPlanner.Plan.Armed -> FeatureState.Active("Reinicia em ${plan.remainingMs / 60_000} min sem desbloqueio" + if (cfg.dryRun) " (ensaio)" else "")
            plan is RebootPlanner.Plan.NotArmedBfu -> FeatureState.Active("Aparelho em BFU desde o boot; timer arma no próximo desbloqueio")
            plan is RebootPlanner.Plan.NoBaseline -> FeatureState.Inactive("Aguardando primeiro desbloqueio verificado")
            else -> FeatureState.Active("Vencido; executando")
        }
        return PolicyCheck("auto_reboot", "Reinicialização automática (${cfg.intervalMs / 3_600_000} h)", state, weight = 3)
    }
}
