package com.shieldpericias.sentinel.core.inactivity

import com.shieldpericias.sentinel.core.time.ClockSnapshot
import com.shieldpericias.sentinel.core.time.Durations

/**
 * Planejamento do Auto-Reboot. Semântica: reiniciar quando o aparelho estiver
 * há [intervalMs] sem desbloqueio verificado DESDE O BOOT ATUAL. Após um reboot
 * o aparelho já está em BFU (Before First Unlock); não há motivo para reiniciar
 * novamente até o próximo desbloqueio — por isso o planejador só arma o timer
 * quando o último desbloqueio ocorreu neste boot.
 */
object RebootPlanner {

    val DEFAULT_INTERVAL_MS: Long = 3 * Durations.HOUR_MS

    /** Intervalos oferecidos na interface. */
    val ALLOWED_INTERVALS_MS: List<Long> = listOf(
        30 * Durations.MINUTE_MS,
        1 * Durations.HOUR_MS,
        2 * Durations.HOUR_MS,
        3 * Durations.HOUR_MS,
        6 * Durations.HOUR_MS,
        12 * Durations.HOUR_MS,
        24 * Durations.HOUR_MS,
    )

    sealed class Plan {
        /** Timer armado: reiniciar quando elapsedRealtime atingir [atElapsedMs]. */
        data class Armed(val atElapsedMs: Long, val remainingMs: Long) : Plan()

        /** Prazo vencido no boot atual: reiniciar agora (se permitido). */
        data object Due : Plan()

        /** Nenhum desbloqueio neste boot: aparelho já está em BFU, nada a fazer. */
        data object NotArmedBfu : Plan()

        /** Sem baseline registrado. */
        data object NoBaseline : Plan()
    }

    fun plan(state: InactivityLedgerState, now: ClockSnapshot, intervalMs: Long): Plan {
        val unlockBoot = state.lastVerifiedUnlockBootCount ?: return Plan.NoBaseline
        val unlockElapsed = state.lastVerifiedUnlockElapsedMs ?: return Plan.NoBaseline
        if (unlockBoot != now.bootCount) return Plan.NotArmedBfu
        val at = unlockElapsed + intervalMs
        val remaining = at - now.elapsedRealtimeMs
        return if (remaining <= 0) Plan.Due else Plan.Armed(at, remaining)
    }
}
