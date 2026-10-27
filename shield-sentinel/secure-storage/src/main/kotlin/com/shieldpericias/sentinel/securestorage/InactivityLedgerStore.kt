package com.shieldpericias.sentinel.securestorage

import com.shieldpericias.sentinel.core.inactivity.InactivityLedger
import com.shieldpericias.sentinel.core.inactivity.InactivityLedgerState
import com.shieldpericias.sentinel.core.time.ClockSnapshot

/**
 * Persistência do livro-razão de inatividade em Device Protected Storage (com HMAC),
 * para que o Auto-Wipe consiga avaliar o prazo mesmo em BFU após reinicializações.
 * Contém apenas timestamps e contadores — nenhum segredo.
 */
class InactivityLedgerStore(private val kv: DeviceProtectedKv, private val ledger: InactivityLedger = InactivityLedger()) {

    private val lock = Any()

    fun load(): InactivityLedgerState = synchronized(lock) {
        InactivityLedgerState(
            lastVerifiedUnlockEpochMs = kv.getLong("ul.epoch"),
            lastVerifiedUnlockBootCount = kv.getInt("ul.boot"),
            lastVerifiedUnlockElapsedMs = kv.getLong("ul.elapsed"),
            poweredSinceUnlockMs = kv.getLong("powered") ?: 0L,
            lastObservationBootCount = kv.getInt("ob.boot"),
            lastObservationElapsedMs = kv.getLong("ob.elapsed"),
            lastObservationEpochMs = kv.getLong("ob.epoch"),
            clockAnomalyDetected = kv.getBoolean("anomaly", false),
        )
    }

    fun save(s: InactivityLedgerState) = synchronized(lock) {
        s.lastVerifiedUnlockEpochMs?.let { kv.putLong("ul.epoch", it) }
        s.lastVerifiedUnlockBootCount?.let { kv.putInt("ul.boot", it) }
        s.lastVerifiedUnlockElapsedMs?.let { kv.putLong("ul.elapsed", it) }
        kv.putLong("powered", s.poweredSinceUnlockMs)
        s.lastObservationBootCount?.let { kv.putInt("ob.boot", it) }
        s.lastObservationElapsedMs?.let { kv.putLong("ob.elapsed", it) }
        s.lastObservationEpochMs?.let { kv.putLong("ob.epoch", it) }
        kv.putBoolean("anomaly", s.clockAnomalyDetected)
    }

    /** Registra desbloqueio VERIFICADO (único evento que zera os contadores). */
    fun recordVerifiedUnlock(now: ClockSnapshot): InactivityLedgerState = synchronized(lock) {
        ledger.recordVerifiedUnlock(load(), now).also { save(it) }
    }

    /** Observação periódica (boot, watchdog, antes de reiniciar). */
    fun observe(now: ClockSnapshot): InactivityLedgerState = synchronized(lock) {
        ledger.observe(load(), now).also { save(it) }
    }

    fun integrityOk(): Boolean = kv.verifyAll().isEmpty()
}
