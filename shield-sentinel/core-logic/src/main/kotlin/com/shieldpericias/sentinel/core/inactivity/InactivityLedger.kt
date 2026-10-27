package com.shieldpericias.sentinel.core.inactivity

import com.shieldpericias.sentinel.core.time.ClockSnapshot

/**
 * Estado persistido do "livro-razão" de inatividade.
 *
 * O contador de desbloqueio é compartilhado pelo Auto-Reboot (horas) e pelo
 * Auto-Wipe (dias). Ele só é zerado por [InactivityLedger.recordVerifiedUnlock],
 * nunca por reinicializações.
 */
data class InactivityLedgerState(
    val lastVerifiedUnlockEpochMs: Long? = null,
    val lastVerifiedUnlockBootCount: Int? = null,
    val lastVerifiedUnlockElapsedMs: Long? = null,
    /** Tempo LIGADO acumulado desde o último desbloqueio verificado (limite inferior da inatividade real). */
    val poweredSinceUnlockMs: Long = 0L,
    val lastObservationBootCount: Int? = null,
    val lastObservationElapsedMs: Long? = null,
    val lastObservationEpochMs: Long? = null,
    /** Relógio de parede retrocedeu ou monotônico inconsistente — torna a prova inconclusiva. */
    val clockAnomalyDetected: Boolean = false,
    val version: Int = 1,
) {
    val hasBaseline: Boolean get() = lastVerifiedUnlockEpochMs != null
}

enum class EvidencePolicy {
    /** Exige relógio de parede E tempo ligado acumulado ≥ limiar (padrão, à prova de relógio alterado). */
    STRICT,

    /** Apenas relógio de parede (menos seguro; relógio pode ser manipulado). */
    WALL_CLOCK_ONLY,
}

enum class InactivityDecision {
    /** Prazo comprovadamente excedido — a ação pode ser executada. */
    THRESHOLD_REACHED,

    /** Ainda dentro do prazo. */
    WAIT,

    /** Não é possível comprovar (sem baseline, anomalia de relógio, dados inconsistentes). NUNCA agir. */
    INCONCLUSIVE,
}

data class InactivityVerdict(
    val decision: InactivityDecision,
    val wallClockElapsedMs: Long?,
    val poweredElapsedMs: Long,
    val remainingMs: Long?,
    val reasons: List<String>,
)

/**
 * Lógica pura dos contadores. Todas as funções são determinísticas e recebem o
 * [ClockSnapshot] explicitamente, o que permite testar relógio alterado,
 * reboot, Direct Boot etc. sem Android.
 */
class InactivityLedger(
    /** Tolerância para pequenas correções de relógio (NTP) antes de marcar anomalia. */
    private val clockBackwardToleranceMs: Long = 5 * 60_000L,
) {

    fun recordVerifiedUnlock(state: InactivityLedgerState, now: ClockSnapshot): InactivityLedgerState =
        state.copy(
            lastVerifiedUnlockEpochMs = now.epochMs,
            lastVerifiedUnlockBootCount = now.bootCount,
            lastVerifiedUnlockElapsedMs = now.elapsedRealtimeMs,
            poweredSinceUnlockMs = 0L,
            lastObservationBootCount = now.bootCount,
            lastObservationElapsedMs = now.elapsedRealtimeMs,
            lastObservationEpochMs = now.epochMs,
            clockAnomalyDetected = false,
        )

    /**
     * Deve ser chamado periodicamente (watchdog, boot, antes de reiniciar).
     * Acumula tempo ligado de forma monotônica e detecta anomalias de relógio.
     */
    fun observe(state: InactivityLedgerState, now: ClockSnapshot): InactivityLedgerState {
        if (!state.hasBaseline) {
            return state.copy(
                lastObservationBootCount = now.bootCount,
                lastObservationElapsedMs = now.elapsedRealtimeMs,
                lastObservationEpochMs = now.epochMs,
            )
        }
        var anomaly = state.clockAnomalyDetected
        var powered = state.poweredSinceUnlockMs

        val lastBoot = state.lastObservationBootCount
        val lastElapsed = state.lastObservationElapsedMs
        val lastEpoch = state.lastObservationEpochMs

        if (lastBoot != null && lastElapsed != null && lastBoot == now.bootCount) {
            val delta = now.elapsedRealtimeMs - lastElapsed
            if (delta < 0) anomaly = true else powered += delta
        } else {
            // Novo boot (ou primeira observação após o desbloqueio em outro boot):
            // o tempo desde o boot atual é tempo ligado comprovado.
            if (lastBoot != null && now.bootCount < lastBoot) anomaly = true
            powered += now.elapsedRealtimeMs.coerceAtLeast(0L)
        }
        if (lastEpoch != null && now.epochMs < lastEpoch - clockBackwardToleranceMs) anomaly = true

        return state.copy(
            poweredSinceUnlockMs = powered,
            lastObservationBootCount = now.bootCount,
            lastObservationElapsedMs = now.elapsedRealtimeMs,
            lastObservationEpochMs = now.epochMs,
            clockAnomalyDetected = anomaly,
        )
    }

    /**
     * Avalia o prazo. Chame [observe] antes para incorporar o tempo corrente.
     * Quando não é possível comprovar o período, retorna INCONCLUSIVE — nunca age.
     */
    fun evaluate(
        state: InactivityLedgerState,
        now: ClockSnapshot,
        thresholdMs: Long,
        policy: EvidencePolicy = EvidencePolicy.STRICT,
    ): InactivityVerdict {
        val reasons = mutableListOf<String>()
        val unlockEpoch = state.lastVerifiedUnlockEpochMs
        if (unlockEpoch == null) {
            reasons += "no_verified_unlock_baseline"
            return InactivityVerdict(InactivityDecision.INCONCLUSIVE, null, state.poweredSinceUnlockMs, null, reasons)
        }
        if (thresholdMs <= 0) {
            reasons += "invalid_threshold"
            return InactivityVerdict(InactivityDecision.INCONCLUSIVE, null, state.poweredSinceUnlockMs, null, reasons)
        }
        val wall = now.epochMs - unlockEpoch
        if (state.clockAnomalyDetected) reasons += "clock_anomaly"
        if (wall < 0) reasons += "wall_clock_before_unlock"
        if (reasons.isNotEmpty()) {
            return InactivityVerdict(InactivityDecision.INCONCLUSIVE, wall, state.poweredSinceUnlockMs, null, reasons)
        }
        val powered = state.poweredSinceUnlockMs
        val wallReached = wall >= thresholdMs
        val poweredReached = powered >= thresholdMs
        val reached = when (policy) {
            EvidencePolicy.STRICT -> wallReached && poweredReached
            EvidencePolicy.WALL_CLOCK_ONLY -> wallReached
        }
        return if (reached) {
            reasons += "threshold_reached"
            InactivityVerdict(InactivityDecision.THRESHOLD_REACHED, wall, powered, 0L, reasons)
        } else {
            val remaining = when (policy) {
                EvidencePolicy.STRICT -> maxOf(thresholdMs - wall, thresholdMs - powered)
                EvidencePolicy.WALL_CLOCK_ONLY -> thresholdMs - wall
            }
            if (!wallReached) reasons += "wall_clock_below_threshold"
            if (policy == EvidencePolicy.STRICT && !poweredReached) reasons += "powered_time_below_threshold"
            InactivityVerdict(InactivityDecision.WAIT, wall, powered, remaining.coerceAtLeast(0L), reasons)
        }
    }
}
