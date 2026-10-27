package com.shieldpericias.sentinel.watchdog

import android.app.KeyguardManager
import android.content.Context
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.core.time.ClockSource
import com.shieldpericias.sentinel.securestorage.InactivityLedgerStore
import com.shieldpericias.sentinel.securestorage.VerifiedUnlockProbe

/**
 * Decide se um evento de desbloqueio conta como "válido e verificável":
 *  1. o aparelho precisa ter bloqueio seguro;
 *  2. a chave auth-bound do Keystore precisa estar utilizável (autenticação forte
 *     nos últimos segundos, atestada pelo sistema).
 * Só então o livro-razão é zerado e os ouvintes são notificados.
 */
class UnlockVerifier(
    private val context: Context,
    private val probe: VerifiedUnlockProbe,
    private val ledgerStore: InactivityLedgerStore,
    private val clock: ClockSource,
    private val audit: AuditLog,
) {
    fun interface Listener { fun onVerifiedUnlock() }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners += l }

    @Volatile var lastResult: String = "nenhum"
        private set

    fun onUnlockSignal(source: String): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java)
        if (km?.isDeviceSecure != true) {
            lastResult = "ignorado: sem bloqueio seguro"
            audit.append(AuditTypes.UNLOCK_NOT_VERIFIED, "$source: sem bloqueio seguro")
            return false
        }
        if (km.isDeviceLocked) {
            lastResult = "ignorado: ainda bloqueado"
            return false
        }
        return when (val r = probe.probe()) {
            VerifiedUnlockProbe.Result.Authenticated -> {
                ledgerStore.recordVerifiedUnlock(clock.snapshot())
                lastResult = "verificado ($source)"
                audit.append(AuditTypes.VERIFIED_UNLOCK, source)
                listeners.forEach { runCatching { it.onVerifiedUnlock() } }
                true
            }
            VerifiedUnlockProbe.Result.NotAuthenticated -> {
                lastResult = "não verificado: sem autenticação forte recente"
                audit.append(AuditTypes.UNLOCK_NOT_VERIFIED, "$source: chave auth-bound recusada")
                false
            }
            VerifiedUnlockProbe.Result.KeyInvalidated -> {
                lastResult = "chave invalidada (credencial alterada); recriada"
                audit.append(AuditTypes.UNLOCK_NOT_VERIFIED, "$source: chave invalidada")
                false
            }
            is VerifiedUnlockProbe.Result.Unavailable -> {
                lastResult = "indisponível: ${r.reason}"
                audit.append(AuditTypes.UNLOCK_NOT_VERIFIED, "$source: ${r.reason}")
                false
            }
        }
    }
}
