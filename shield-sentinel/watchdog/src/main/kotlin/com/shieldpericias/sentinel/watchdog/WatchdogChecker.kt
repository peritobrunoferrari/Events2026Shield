package com.shieldpericias.sentinel.watchdog

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import com.shieldpericias.sentinel.antitheft.AutoWipeEngine
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.autoreboot.RebootScheduler
import com.shieldpericias.sentinel.core.AdminMode
import com.shieldpericias.sentinel.core.FeatureState
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.SecurityLevelCalculator
import com.shieldpericias.sentinel.core.SecurityScore
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade
import com.shieldpericias.sentinel.hardening.HardeningController
import com.shieldpericias.sentinel.securestorage.DeviceProtectedKv
import com.shieldpericias.sentinel.securestorage.InactivityLedgerStore
import com.shieldpericias.sentinel.usbsecurity.UsbPolicyController

data class WatchdogReport(
    val generatedAtEpochMs: Long,
    val adminMode: AdminMode,
    val checks: List<PolicyCheck>,
    val score: SecurityScore,
    /** Verificações que estavam ativas no relatório anterior e deixaram de estar. */
    val lostProtections: List<String>,
    val failures: List<String>,
)

/**
 * Monitor de integridade das políticas. Não afirma impedir a própria remoção:
 * apenas detecta e alerta localmente quando uma proteção deixa de estar ativa.
 */
class WatchdogChecker(
    private val context: Context,
    private val facade: DevicePolicyFacade,
    private val usb: UsbPolicyController,
    private val hardening: HardeningController,
    private val reboot: RebootScheduler,
    private val autoWipe: AutoWipeEngine,
    private val ledgerStore: InactivityLedgerStore,
    private val kv: DeviceProtectedKv,
    private val audit: AuditLog,
    private val alerts: LocalAlerts,
) {
    companion object {
        private const val KEY_LAST_RUN = "wd.last_run"
        private const val KEY_LAST_ACTIVE = "wd.last_active"
        private const val KEY_LAST_LEVEL = "wd.last_level"
        private const val KEY_LAST_PERCENT = "wd.last_percent"
        private const val KEY_FAILURES = "wd.failures"
    }

    fun lastRunEpochMs(): Long? = kv.getLong(KEY_LAST_RUN)
    fun lastLevel(): String? = kv.get(KEY_LAST_LEVEL)
    fun lastPercent(): Int? = kv.getInt(KEY_LAST_PERCENT)

    fun run(notify: Boolean = true): WatchdogReport {
        val failures = mutableListOf<String>()
        val checks = mutableListOf<PolicyCheck>()
        val status = facade.status()

        checks += PolicyCheck(
            "admin_mode", "Device Owner ativo",
            when (status.mode) {
                AdminMode.DEVICE_OWNER -> FeatureState.Active("Device Owner")
                AdminMode.PROFILE_OWNER -> FeatureState.Active("Profile Owner — políticas limitadas ao perfil")
                AdminMode.DEVICE_ADMIN -> FeatureState.Inactive("Apenas Device Admin")
                AdminMode.NONE -> FeatureState.Inactive("Sem privilégio administrativo")
            },
            weight = 5, critical = true,
        )

        safely("usb", failures) { checks += usb.checks() }
        safely("auto_reboot", failures) { checks += reboot.check() }
        safely("auto_wipe", failures) { checks += autoWipe.check() }
        safely("hardening", failures) { checks += hardening.checks() }
        safely("insecure", failures) { checks += hardening.insecureSettings() }

        // Integridade das configurações (HMAC em Device Protected Storage).
        val badKeys = runCatching { kv.verifyAll() }.getOrDefault(emptyList())
        val ledgerOk = runCatching { ledgerStore.integrityOk() }.getOrDefault(false)
        checks += PolicyCheck(
            "config_integrity", "Integridade das configurações",
            if (badKeys.isEmpty() && ledgerOk) FeatureState.Active("MACs válidos") else FeatureState.Error("Entradas inválidas: ${badKeys.joinToString()}"),
            weight = 2, critical = true,
        )
        if (badKeys.isNotEmpty()) audit.append(AuditTypes.INTEGRITY_FAILURE, badKeys.joinToString())

        val am = context.getSystemService(AlarmManager::class.java)
        checks += PolicyCheck("perm.exact_alarm", "Alarmes exatos", if (am?.canScheduleExactAlarms() == true) FeatureState.Active() else FeatureState.Inactive("Auto-Reboot usa janela inexata"), weight = 1)
        val nm = context.getSystemService(NotificationManager::class.java)
        checks += PolicyCheck("perm.notifications", "Notificações permitidas", if (nm?.areNotificationsEnabled() == true) FeatureState.Active() else FeatureState.Inactive("Alertas locais não serão exibidos"), weight = 1)

        val lastRun = lastRunEpochMs()
        checks += PolicyCheck("wd.last_run", "Última verificação de políticas",
            if (lastRun == null) FeatureState.Inactive("Primeira execução") else FeatureState.Active(java.util.Date(lastRun).toString()), weight = 0)

        val score = SecurityLevelCalculator.compute(status.mode, checks)

        // Comparação com a execução anterior → alertas de perda de proteção.
        val previousActive = kv.get(KEY_LAST_ACTIVE)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val nowActive = checks.filter { it.state.isActive }.map { it.id }.toSet()
        val lost = previousActive.filter { it !in nowActive && checks.any { c -> c.id == it } }

        val now = System.currentTimeMillis()
        kv.putLong(KEY_LAST_RUN, now)
        kv.put(KEY_LAST_ACTIVE, nowActive.joinToString(","))
        kv.put(KEY_LAST_LEVEL, score.level.name)
        kv.putInt(KEY_LAST_PERCENT, score.percent)
        kv.put(KEY_FAILURES, failures.joinToString(","))

        audit.append(AuditTypes.WATCHDOG_RUN, "${score.level} ${score.percent}% perdas=${lost.size} falhas=${failures.size}")
        if (notify && lost.isNotEmpty()) {
            audit.append(AuditTypes.WATCHDOG_ALERT, lost.joinToString(","))
            alerts.protectionLost(lost.mapNotNull { id -> checks.firstOrNull { it.id == id }?.title })
        }
        return WatchdogReport(now, status.mode, checks, score, lost, failures)
    }

    private inline fun safely(tag: String, failures: MutableList<String>, block: () -> Unit) {
        try { block() } catch (e: Exception) { failures += "$tag: ${e.javaClass.simpleName}" }
    }
}
