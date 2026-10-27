package com.shieldpericias.sentinel.core.inactivity

import com.shieldpericias.sentinel.core.time.Durations

/** Configuração do Auto-Wipe por inatividade. Desativado por padrão. */
data class AutoWipeConfig(
    val enabled: Boolean = false,
    val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    val evidencePolicy: EvidencePolicy = EvidencePolicy.STRICT,
    /** Em modo de ensaio nenhuma apagamento real ocorre; só registro em auditoria. */
    val dryRun: Boolean = true,
) {
    companion object {
        val DEFAULT_THRESHOLD_MS: Long = 7 * Durations.DAY_MS
        val ALLOWED_THRESHOLDS_MS: List<Long> = listOf(3, 7, 14, 30).map { it * Durations.DAY_MS }

        fun isAllowedThreshold(ms: Long) = ms in ALLOWED_THRESHOLDS_MS
    }
}
