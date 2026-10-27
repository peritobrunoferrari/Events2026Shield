package com.shieldpericias.sentinel.autoreboot

import com.shieldpericias.sentinel.core.inactivity.RebootPlanner
import com.shieldpericias.sentinel.securestorage.DeviceProtectedKv

data class AutoRebootConfig(
    val enabled: Boolean = false,
    val intervalMs: Long = RebootPlanner.DEFAULT_INTERVAL_MS,
    /** Modo de ensaio: registra em auditoria em vez de reiniciar. Padrão desligado em produção. */
    val dryRun: Boolean = false,
)

class AutoRebootConfigStore(private val kv: DeviceProtectedKv) {
    fun load() = AutoRebootConfig(
        enabled = kv.getBoolean("ar.enabled", false),
        intervalMs = kv.getLong("ar.interval") ?: RebootPlanner.DEFAULT_INTERVAL_MS,
        dryRun = kv.getBoolean("ar.dryrun", false),
    )

    fun save(c: AutoRebootConfig) {
        require(c.intervalMs in RebootPlanner.ALLOWED_INTERVALS_MS) { "intervalo não permitido" }
        kv.putBoolean("ar.enabled", c.enabled)
        kv.putLong("ar.interval", c.intervalMs)
        kv.putBoolean("ar.dryrun", c.dryRun)
    }
}
