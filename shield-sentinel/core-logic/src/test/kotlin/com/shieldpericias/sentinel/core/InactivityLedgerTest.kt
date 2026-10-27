package com.shieldpericias.sentinel.core

import com.shieldpericias.sentinel.core.inactivity.EvidencePolicy
import com.shieldpericias.sentinel.core.inactivity.InactivityDecision
import com.shieldpericias.sentinel.core.inactivity.InactivityLedger
import com.shieldpericias.sentinel.core.inactivity.InactivityLedgerState
import com.shieldpericias.sentinel.core.inactivity.RebootPlanner
import com.shieldpericias.sentinel.core.time.Durations.DAY_MS
import com.shieldpericias.sentinel.core.time.Durations.HOUR_MS
import com.shieldpericias.sentinel.core.time.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InactivityLedgerTest {
    private val ledger = InactivityLedger()
    private val sevenDays = 7 * DAY_MS

    @Test
    fun `sem baseline nunca decide apagar`() {
        val clock = FakeClock()
        val v = ledger.evaluate(InactivityLedgerState(), clock.snapshot(), sevenDays)
        assertEquals(InactivityDecision.INCONCLUSIVE, v.decision)
        assertTrue("no_verified_unlock_baseline" in v.reasons)
    }

    @Test
    fun `dentro do prazo espera`() {
        val clock = FakeClock()
        var s = ledger.recordVerifiedUnlock(InactivityLedgerState(), clock.snapshot())
        clock.advance(2 * DAY_MS)
        s = ledger.observe(s, clock.snapshot())
        val v = ledger.evaluate(s, clock.snapshot(), sevenDays)
        assertEquals(InactivityDecision.WAIT, v.decision)
        assertEquals(5 * DAY_MS, v.remainingMs)
    }

    @Test
    fun `reinicializacoes automaticas de 3h nao zeram o contador de 7 dias`() {
        val clock = FakeClock()
        var s = ledger.recordVerifiedUnlock(InactivityLedgerState(), clock.snapshot())
        // 7 dias = 56 ciclos de 3 horas, cada um terminando em reboot.
        repeat(56) {
            clock.advance(3 * HOUR_MS)
            s = ledger.observe(s, clock.snapshot())   // observação antes do reboot
            clock.reboot(offMs = 30_000)               // 30 s desligado
            s = ledger.observe(s, clock.snapshot())   // LOCKED_BOOT_COMPLETED
        }
        val v = ledger.evaluate(s, clock.snapshot(), sevenDays)
        assertEquals(InactivityDecision.THRESHOLD_REACHED, v.decision)
        assertTrue(v.poweredElapsedMs >= sevenDays)
    }

    @Test
    fun `relogio adiantado sem tempo ligado nao apaga em modo STRICT`() {
        val clock = FakeClock()
        var s = ledger.recordVerifiedUnlock(InactivityLedgerState(), clock.snapshot())
        clock.epochMs += 30 * DAY_MS  // alguém adiantou a data 30 dias
        clock.elapsedRealtimeMs += 10 * 60_000L
        s = ledger.observe(s, clock.snapshot())
        val strict = ledger.evaluate(s, clock.snapshot(), sevenDays, EvidencePolicy.STRICT)
        assertEquals(InactivityDecision.WAIT, strict.decision)
        assertTrue("powered_time_below_threshold" in strict.reasons)
        val weak = ledger.evaluate(s, clock.snapshot(), sevenDays, EvidencePolicy.WALL_CLOCK_ONLY)
        assertEquals(InactivityDecision.THRESHOLD_REACHED, weak.decision)
    }

    @Test
    fun `relogio atrasado gera anomalia e torna inconclusivo`() {
        val clock = FakeClock()
        var s = ledger.recordVerifiedUnlock(InactivityLedgerState(), clock.snapshot())
        clock.advance(DAY_MS)
        s = ledger.observe(s, clock.snapshot())
        clock.epochMs -= 2 * DAY_MS
        clock.elapsedRealtimeMs += 60_000
        s = ledger.observe(s, clock.snapshot())
        assertTrue(s.clockAnomalyDetected)
        val v = ledger.evaluate(s, clock.snapshot(), sevenDays)
        assertEquals(InactivityDecision.INCONCLUSIVE, v.decision)
    }

    @Test
    fun `desbloqueio verificado zera contador e limpa anomalia`() {
        val clock = FakeClock()
        var s = ledger.recordVerifiedUnlock(InactivityLedgerState(), clock.snapshot())
        clock.advance(6 * DAY_MS)
        s = ledger.observe(s, clock.snapshot()).copy(clockAnomalyDetected = true)
        s = ledger.recordVerifiedUnlock(s, clock.snapshot())
        assertEquals(0L, s.poweredSinceUnlockMs)
        assertEquals(false, s.clockAnomalyDetected)
        clock.advance(DAY_MS)
        s = ledger.observe(s, clock.snapshot())
        assertEquals(InactivityDecision.WAIT, ledger.evaluate(s, clock.snapshot(), sevenDays).decision)
    }

    @Test
    fun `aparelho desligado por semanas nao comprova inatividade em STRICT`() {
        val clock = FakeClock()
        var s = ledger.recordVerifiedUnlock(InactivityLedgerState(), clock.snapshot())
        clock.reboot(offMs = 20 * DAY_MS)
        s = ledger.observe(s, clock.snapshot())
        val v = ledger.evaluate(s, clock.snapshot(), sevenDays)
        assertEquals(InactivityDecision.WAIT, v.decision)
    }

    @Test
    fun `planejador de reboot arma apenas no boot do desbloqueio`() {
        val clock = FakeClock()
        val s = ledger.recordVerifiedUnlock(InactivityLedgerState(), clock.snapshot())
        clock.advance(HOUR_MS)
        val armed = RebootPlanner.plan(s, clock.snapshot(), 3 * HOUR_MS)
        assertTrue(armed is RebootPlanner.Plan.Armed && armed.remainingMs == 2 * HOUR_MS)
        clock.advance(2 * HOUR_MS)
        assertEquals(RebootPlanner.Plan.Due, RebootPlanner.plan(s, clock.snapshot(), 3 * HOUR_MS))
        clock.reboot()
        assertEquals(RebootPlanner.Plan.NotArmedBfu, RebootPlanner.plan(s, clock.snapshot(), 3 * HOUR_MS))
        assertEquals(RebootPlanner.Plan.NoBaseline, RebootPlanner.plan(InactivityLedgerState(), clock.snapshot(), 3 * HOUR_MS))
    }
}
