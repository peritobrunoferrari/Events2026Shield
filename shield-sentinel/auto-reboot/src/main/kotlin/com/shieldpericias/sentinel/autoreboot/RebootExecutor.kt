package com.shieldpericias.sentinel.autoreboot

import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.deviceowner.DevicePolicyFacade

/** Abstração do reboot para que testes e o modo de ensaio nunca reiniciem de verdade. */
interface RebootExecutor {
    fun reboot(reason: String): PolicyResult
}

class RealRebootExecutor(private val facade: DevicePolicyFacade) : RebootExecutor {
    override fun reboot(reason: String): PolicyResult = facade.reboot()
}

class FakeRebootExecutor : RebootExecutor {
    val calls = mutableListOf<String>()
    var result: PolicyResult = PolicyResult.Applied
    override fun reboot(reason: String): PolicyResult { calls += reason; return result }
}
