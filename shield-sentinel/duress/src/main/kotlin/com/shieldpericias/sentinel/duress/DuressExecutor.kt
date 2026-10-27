package com.shieldpericias.sentinel.duress

import com.shieldpericias.sentinel.antitheft.WipeController
import com.shieldpericias.sentinel.antitheft.WipeRequest
import com.shieldpericias.sentinel.audit.AuditLog
import com.shieldpericias.sentinel.audit.AuditTypes
import com.shieldpericias.sentinel.autoreboot.RebootExecutor
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.core.duress.DuressAction
import com.shieldpericias.sentinel.core.duress.DuressPolicy
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade

data class DuressOutcome(val action: DuressAction, val results: List<PolicyResult>, val simulated: Boolean)

/**
 * Executa a política de emergência ao receber o PIN duress na UI do Sentinel.
 * Depois de configurada e habilitada, NÃO pede confirmação adicional.
 * FACTORY_RESET só executa com reconhecimento explícito do proprietário; eSIM é
 * preservada por padrão. Em modo de ensaio o apagamento é simulado.
 */
class DuressExecutor(
    private val facade: DevicePolicyFacade,
    private val rebootExecutor: RebootExecutor,
    private val wipeControllerProvider: () -> WipeController,
    private val audit: AuditLog,
) {
    fun execute(policy: DuressPolicy): DuressOutcome? {
        if (!policy.isExecutable) {
            audit.append(AuditTypes.EMERGENCY_REFUSED, "duress: política não executável (${policy.action}, enabled=${policy.enabled})")
            return null
        }
        audit.append(AuditTypes.DURESS_TRIGGERED, policy.action.name)
        return when (policy.action) {
            DuressAction.LOCK -> DuressOutcome(policy.action, listOf(facade.lockNow()), simulated = false)
            DuressAction.LOCK_AND_REBOOT -> {
                val lock = facade.lockNow()
                val reboot = rebootExecutor.reboot("duress")
                DuressOutcome(policy.action, listOf(lock, reboot), simulated = false)
            }
            DuressAction.FACTORY_RESET -> {
                val wc = wipeControllerProvider()
                val scope = wc.availableScope()
                    ?: return DuressOutcome(policy.action, listOf(PolicyResult.Unsupported("sem escopo de apagamento")), simulated = false)
                val out = wc.wipe(WipeRequest(scope, "Shield Sentinel: PIN de emergência", "duress", wipeEsim = policy.wipeEsim))
                DuressOutcome(policy.action, listOf(out.result), simulated = out.simulated)
            }
        }
    }
}
