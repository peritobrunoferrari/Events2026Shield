package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.core.SecurityLevel
import com.shieldpericias.sentinel.coresecurity.AdminStatus
import com.shieldpericias.sentinel.coresecurity.DeviceInfo
import com.shieldpericias.sentinel.ui.Route
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.CheckRow
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.KeyValue
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.SectionTitle
import com.shieldpericias.sentinel.watchdog.WatchdogReport

data class DashboardData(val report: WatchdogReport, val device: DeviceInfo, val status: AdminStatus)

private fun SecurityLevel.ptBr() = when (this) {
    SecurityLevel.CRITICAL -> "CRÍTICO"
    SecurityLevel.LOW -> "BAIXO"
    SecurityLevel.MODERATE -> "MODERADO"
    SecurityLevel.ELEVATED -> "ELEVADO"
    SecurityLevel.HIGH -> "ALTO"
}

@Composable
fun DashboardScreen(graph: SentinelGraph, nav: NavHostController, snack: SnackbarHostState) {
    val vm: ScreenViewModel<DashboardData> = viewModel(key = "dashboard") {
        ScreenViewModel { DashboardData(graph.watchdog.run(notify = false), DeviceInfo.collect(), graph.facade.status()) }
    }
    ViewModelScreen(vm, snack) { d ->
        val score = d.report.score
        ScrollBody(
            {
                InfoCard("Nível de segurança: ${score.level.ptBr()}") {
                    LinearProgressIndicator(progress = { score.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("${score.percent}% das políticas disponíveis estão ativas (${score.activeCount}/${score.availableCount}); ${score.unavailableCount} indisponíveis neste aparelho/modo.",
                        style = MaterialTheme.typography.bodySmall)
                    if (score.criticalFailures.isNotEmpty()) Text("Falhas críticas: ${score.criticalFailures.joinToString()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Paragraph("Síntese das políticas verificadas agora. Não é promessa de resistência à extração forense.")
                }
            },
            {
                InfoCard("Aparelho") {
                    KeyValue("Modelo", "${d.device.manufacturer} ${d.device.model}")
                    KeyValue("Android", "${d.device.androidVersion} (API ${d.device.sdkInt})")
                    KeyValue("Patch de segurança", d.device.securityPatch)
                    KeyValue("Modo administrativo", d.status.mode.name)
                    KeyValue("Última verificação", fmtDate(d.report.generatedAtEpochMs))
                }
            },
            {
                SectionTitle("Resumo das proteções")
                InfoCard {
                    d.report.checks.filter { it.weight >= 2 }.forEach { CheckRow(it) }
                }
            },
            {
                SectionTitle("Módulos")
                val routes = Route.entries.filter { it != Route.DASHBOARD }
                Column {
                    routes.chunked(2).forEach { pair ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { r ->
                                OutlinedButton(onClick = { nav.navigate(r.path) }, modifier = Modifier.weight(1f)) {
                                    Text(r.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 2)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Reverificar políticas") }
                Spacer(Modifier.height(16.dp))
            },
        )
    }
}
