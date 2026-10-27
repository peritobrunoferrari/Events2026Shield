package com.shieldpericias.sentinel.core

import com.shieldpericias.sentinel.core.audit.HashChain
import com.shieldpericias.sentinel.core.usb.UsbImpact
import com.shieldpericias.sentinel.core.usb.UsbProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityLevelAndChainTest {
    private fun check(id: String, state: FeatureState, weight: Int = 1, critical: Boolean = false) =
        PolicyCheck(id, id, state, weight = weight, critical = critical)

    @Test
    fun `sem device owner o nivel e limitado a LOW`() {
        val checks = listOf(check("a", FeatureState.Active()), check("b", FeatureState.Active()))
        assertEquals(SecurityLevel.LOW, SecurityLevelCalculator.compute(AdminMode.NONE, checks).level)
        assertEquals(SecurityLevel.HIGH, SecurityLevelCalculator.compute(AdminMode.DEVICE_OWNER, checks).level)
    }

    @Test
    fun `indisponiveis nao contam contra e criticos limitam`() {
        val checks = listOf(
            check("a", FeatureState.Active(), weight = 3),
            check("b", FeatureState.Unavailable("sem suporte"), weight = 5),
            check("c", FeatureState.Inactive(), weight = 1, critical = true),
        )
        val score = SecurityLevelCalculator.compute(AdminMode.DEVICE_OWNER, checks)
        assertEquals(75, score.percent)
        assertEquals(1, score.unavailableCount)
        assertEquals(listOf("c"), score.criticalFailures)
        assertEquals(SecurityLevel.MODERATE, score.level)
    }

    @Test
    fun `cadeia de hash detecta alteracao`() {
        val h1 = HashChain.next(HashChain.GENESIS, "evento1")
        val h2 = HashChain.next(h1, "evento2")
        assertEquals(-1, HashChain.firstBreak(listOf(Triple(HashChain.GENESIS, "evento1", h1), Triple(h1, "evento2", h2))))
        assertEquals(1, HashChain.firstBreak(listOf(Triple(HashChain.GENESIS, "evento1", h1), Triple(h1, "evento2-alterado", h2))))
    }

    @Test
    fun `perfil maximo declara impacto em ADB e Android Auto`() {
        assertTrue(UsbImpact.ADB_BLOCKED in UsbProfile.MAXIMUM.impacts)
        assertTrue(UsbImpact.ANDROID_AUTO_USB_BLOCKED in UsbProfile.MAXIMUM.impacts)
        assertTrue(UsbImpact.ADB_BLOCKED !in UsbProfile.ELEVATED.impacts)
        assertTrue(UsbProfile.COMPATIBILITY.impacts.isEmpty())
    }
}
