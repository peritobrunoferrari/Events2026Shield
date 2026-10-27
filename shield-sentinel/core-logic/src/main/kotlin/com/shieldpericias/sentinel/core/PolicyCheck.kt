package com.shieldpericias.sentinel.core

/**
 * Uma verificação individual exibida no Watchdog/Dashboard.
 *
 * @param weight peso na síntese do nível de segurança (0 = informativo).
 */
data class PolicyCheck(
    val id: String,
    val title: String,
    val state: FeatureState,
    val detail: String? = null,
    val weight: Int = 1,
    val critical: Boolean = false,
)

enum class SecurityLevel { CRITICAL, LOW, MODERATE, ELEVATED, HIGH }

data class SecurityScore(
    val level: SecurityLevel,
    /** 0..100 — percentual ponderado de verificações ativas entre as disponíveis. */
    val percent: Int,
    val activeCount: Int,
    val availableCount: Int,
    val unavailableCount: Int,
    val errorCount: Int,
    val criticalFailures: List<String>,
)

/**
 * Sintetiza o nível de segurança a partir das políticas verificadas.
 * NÃO é promessa de resistência à extração forense; é uma leitura do estado atual.
 */
object SecurityLevelCalculator {

    fun compute(adminMode: AdminMode, checks: List<PolicyCheck>): SecurityScore {
        val scoring = checks.filter { it.weight > 0 }
        val available = scoring.filter { it.state !is FeatureState.Unavailable }
        val weightTotal = available.sumOf { it.weight }
        val weightActive = available.filter { it.state.isActive }.sumOf { it.weight }
        val percent = if (weightTotal == 0) 0 else (weightActive * 100) / weightTotal
        val criticalFailures = scoring
            .filter { it.critical && it.state !is FeatureState.Active }
            .map { it.id }

        var level = when {
            percent >= 85 -> SecurityLevel.HIGH
            percent >= 65 -> SecurityLevel.ELEVATED
            percent >= 40 -> SecurityLevel.MODERATE
            percent >= 15 -> SecurityLevel.LOW
            else -> SecurityLevel.CRITICAL
        }
        // Sem Device Owner não há como garantir as políticas principais.
        if (adminMode == AdminMode.NONE && level > SecurityLevel.LOW) level = SecurityLevel.LOW
        if (adminMode == AdminMode.DEVICE_ADMIN && level > SecurityLevel.MODERATE) level = SecurityLevel.MODERATE
        if (criticalFailures.isNotEmpty() && level > SecurityLevel.MODERATE) level = SecurityLevel.MODERATE

        return SecurityScore(
            level = level,
            percent = percent,
            activeCount = available.count { it.state.isActive },
            availableCount = available.size,
            unavailableCount = scoring.size - available.size,
            errorCount = scoring.count { it.state is FeatureState.Error },
            criticalFailures = criticalFailures,
        )
    }
}
