package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.inactivity.InactivityLedgerState
import com.shieldpericias.sentinel.core.time.ClockSnapshot
import com.shieldpericias.sentinel.coresecurity.Capability
import com.shieldpericias.sentinel.coresecurity.DeviceInfo
import com.shieldpericias.sentinel.securestorage.VerifiedUnlockProbe
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.CheckRow
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.KeyValue
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.SectionTitle

data class DiagData(
    val device: DeviceInfo,
    val clock: ClockSnapshot,
    val ledger: InactivityLedgerState,
    val keystoreLevel: String,
    val dpIntegrity: List<String>,
    val probe: String,
    val lastUnlockVerification: String,
    val capabilities: List<Capability>,
    val watchdogLastRun: Long?,
    val watchdogLastLevel: String?,
)

@Composable
fun DiagnosticsScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<DiagData> = viewModel(key = "diag") {
        ScreenViewModel {
            DiagData(
                DeviceInfo.collect(), graph.clock.snapshot(), graph.ledgerStore.load(), graph.crypto.masterKeySecurityLevel(),
                graph.dpKv.verifyAll(), probeText(graph.unlockProbe.probe()), graph.unlockVerifier.lastResult,
                graph.capabilities.detect(), graph.watchdog.lastRunEpochMs(), graph.watchdog.lastLevel(),
            )
        }
    }
    ViewModelScreen(vm, snack) { d ->
        ScrollBody(
            {
                InfoCard("Aparelho") {
                    KeyValue("Fabricante / marca", "${d.device.manufacturer} / ${d.device.brand}")
                    KeyValue("Modelo / device", "${d.device.model} / ${d.device.device}")
                    KeyValue("Android / API", "${d.device.androidVersion} / ${d.device.sdkInt}")
                    KeyValue("Build", d.device.buildId)
                    KeyValue("Tipo de build", d.device.buildType)
                    KeyValue("Patch de segurança", d.device.securityPatch)
                }
            },
            {
                InfoCard("Relógios") {
                    KeyValue("Relógio de parede", fmtDate(d.clock.epochMs))
                    KeyValue("elapsedRealtime", fmtDuration(d.clock.elapsedRealtimeMs))
                    KeyValue("Contador de boots", d.clock.bootCount.toString())
                }
            },
            {
                InfoCard("Armazenamento seguro") {
                    KeyValue("Chave mestra (Keystore)", d.keystoreLevel)
                    KeyValue("Integridade Device Protected", if (d.dpIntegrity.isEmpty()) "OK" else "FALHA: ${d.dpIntegrity.joinToString()}")
                    KeyValue("Prova de desbloqueio (chave auth-bound)", d.probe)
                    KeyValue("Última verificação de desbloqueio", d.lastUnlockVerification)
                    Paragraph("A prova de desbloqueio só retorna AUTENTICADO logo após uma autenticação forte (≤ 60 s). Fora dessa janela, 'não autenticado' é o esperado.")
                }
            },
            {
                InfoCard("Livro-razão (bruto)") {
                    KeyValue("Último desbloqueio verificado", fmtDate(d.ledger.lastVerifiedUnlockEpochMs))
                    KeyValue("Boot / elapsed do desbloqueio", "${d.ledger.lastVerifiedUnlockBootCount} / ${fmtDuration(d.ledger.lastVerifiedUnlockElapsedMs)}")
                    KeyValue("Tempo ligado acumulado", fmtDuration(d.ledger.poweredSinceUnlockMs))
                    KeyValue("Última observação", "${fmtDate(d.ledger.lastObservationEpochMs)} (boot ${d.ledger.lastObservationBootCount})")
                    KeyValue("Anomalia de relógio", d.ledger.clockAnomalyDetected.toString())
                    KeyValue("Watchdog", "${fmtDate(d.watchdogLastRun)} — ${d.watchdogLastLevel ?: "—"}")
                    Button(onClick = { vm.act { graph.ledgerStore.observe(graph.clock.snapshot()); graph.watchdog.run(notify = false); "Autoteste executado" } }) { Text("Executar autoteste") }
                }
            },
            {
                SectionTitle("Capacidades")
                InfoCard { d.capabilities.forEach { c -> CheckRow(PolicyCheck(c.id, c.title, c.state, detail = c.note)) } }
                Spacer(Modifier.height(16.dp))
            },
        )
    }
}

private fun probeText(r: VerifiedUnlockProbe.Result) = when (r) {
    VerifiedUnlockProbe.Result.Authenticated -> "AUTENTICADO (autenticação forte recente)"
    VerifiedUnlockProbe.Result.NotAuthenticated -> "não autenticado recentemente"
    VerifiedUnlockProbe.Result.KeyInvalidated -> "chave invalidada e recriada"
    is VerifiedUnlockProbe.Result.Unavailable -> "indisponível: ${r.reason}"
}
