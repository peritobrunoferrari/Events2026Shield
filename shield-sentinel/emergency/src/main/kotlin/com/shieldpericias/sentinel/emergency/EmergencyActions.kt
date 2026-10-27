package com.shieldpericias.sentinel.emergency

import com.shieldpericias.sentinel.autoreboot.RebootExecutor
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.core.emergency.EmergencyActionId
import com.shieldpericias.sentinel.core.usb.UsbProfile
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade
import com.shieldpericias.sentinel.usbsecurity.UsbPolicyController
import com.shieldpericias.sentinel.watchdog.LocalAlerts
import com.shieldpericias.sentinel.watchdog.WatchdogChecker

data class ActionResult(val id: EmergencyActionId, val result: PolicyResult, val message: String)

/** Ação de emergência isolada: sem efeitos colaterais entre ações, cada uma reporta o próprio resultado. */
interface EmergencyAction {
    val id: EmergencyActionId
    fun execute(): ActionResult
}

class LockNowAction(private val facade: DevicePolicyFacade) : EmergencyAction {
    override val id = EmergencyActionId.LOCK_NOW
    override fun execute(): ActionResult {
        val r = facade.lockNow()
        return ActionResult(id, r, if (r.ok) "Aparelho bloqueado" else "Falha ao bloquear: $r")
    }
}

class UsbProtectionAction(private val usb: UsbPolicyController) : EmergencyAction {
    override val id = EmergencyActionId.USB_PROTECTION
    override fun execute(): ActionResult {
        val results = usb.applyProfile(UsbProfile.MAXIMUM)
        val signaling = results.firstOrNull { it.first == UsbPolicyController.CHECK_SIGNALING }?.second
        val any = results.any { it.second.ok }
        val msg = when {
            signaling?.ok == true -> "Sinalização USB desabilitada; transferência e OTG restritos"
            any -> "Transferência/OTG restritos; sinalização USB não controlável neste aparelho/modo"
            else -> "Nenhuma política USB aplicada: ${results.map { it.second }}"
        }
        return ActionResult(id, if (any) PolicyResult.Applied else (signaling ?: PolicyResult.Failed("sem resultado")), msg)
    }
}

class RebootAction(private val executor: RebootExecutor) : EmergencyAction {
    override val id = EmergencyActionId.REBOOT
    override fun execute(): ActionResult {
        val r = executor.reboot("emergency")
        return ActionResult(id, r, if (r.ok) "Reinicialização solicitada (retorna ao estado BFU)" else "Falha ao reiniciar: $r")
    }
}

class SecurityStatusAction(private val checker: WatchdogChecker, private val alerts: LocalAlerts) : EmergencyAction {
    override val id = EmergencyActionId.SECURITY_STATUS
    override fun execute(): ActionResult {
        val report = checker.run(notify = false)
        val text = "Nível ${report.score.level} (${report.score.percent}%). Ativas ${report.score.activeCount}/${report.score.availableCount}."
        alerts.info("Status de segurança", text)
        return ActionResult(id, PolicyResult.Applied, text)
    }
}
