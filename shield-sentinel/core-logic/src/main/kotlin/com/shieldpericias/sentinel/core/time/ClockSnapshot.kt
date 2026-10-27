package com.shieldpericias.sentinel.core.time

/**
 * Leitura simultânea das três fontes de tempo usadas pelos contadores.
 *
 * @param epochMs relógio de parede (pode ser alterado pelo usuário/rede).
 * @param elapsedRealtimeMs monotônico desde o boot (não volta, zera a cada boot).
 * @param bootCount contador de boots do sistema (Settings.Global.BOOT_COUNT).
 */
data class ClockSnapshot(
    val epochMs: Long,
    val elapsedRealtimeMs: Long,
    val bootCount: Int,
)

/** Fonte de tempo abstrata — implementada no Android e por fakes em testes. */
interface ClockSource {
    fun snapshot(): ClockSnapshot
}

/** Relógio controlável para testes. */
class FakeClock(
    var epochMs: Long = 1_700_000_000_000L,
    var elapsedRealtimeMs: Long = 60_000L,
    var bootCount: Int = 1,
) : ClockSource {
    override fun snapshot() = ClockSnapshot(epochMs, elapsedRealtimeMs, bootCount)

    /** Avança ambos os relógios como se o aparelho ficasse ligado. */
    fun advance(ms: Long) { epochMs += ms; elapsedRealtimeMs += ms }

    /** Simula desligamento por [offMs] e novo boot. */
    fun reboot(offMs: Long = 0L) { epochMs += offMs; elapsedRealtimeMs = 1_000L; bootCount += 1 }
}

object Durations {
    const val SECOND_MS = 1_000L
    const val MINUTE_MS = 60 * SECOND_MS
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
}
