package com.shieldpericias.sentinel.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.ChecksCard
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.KeyValue
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.SectionTitle
import com.shieldpericias.sentinel.ui.components.describe
import com.shieldpericias.sentinel.watchdog.SentinelGuardService
import com.shieldpericias.sentinel.watchdog.WatchdogReport

@Composable
fun WatchdogScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<WatchdogReport> = viewModel(key = "watchdog") { ScreenViewModel { graph.watchdog.run(notify = false) } }
    val context = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    ViewModelScreen(vm, snack) { r ->
        ScrollBody(
            {
                InfoCard("Última execução") {
                    KeyValue("Quando", fmtDate(r.generatedAtEpochMs))
                    KeyValue("Nível", "${r.score.level} (${r.score.percent}%)")
                    KeyValue("Proteções perdidas desde a anterior", if (r.lostProtections.isEmpty()) "nenhuma" else r.lostProtections.joinToString())
                    KeyValue("Falhas de execução", if (r.failures.isEmpty()) "nenhuma" else r.failures.joinToString())
                    Row {
                        Button(onClick = { vm.act { graph.watchdogScheduler.onAlarm(); "Watchdog executado" } }) { Text("Executar agora") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { SentinelGuardService.start(context) }) { Text("Reiniciar serviço de guarda") }
                    }
                }
            },
            {
                SectionTitle("Permissões e execução em segundo plano")
                InfoCard {
                    Paragraph("O Watchdog roda a cada 30 min por alarme e também no boot. O serviço de guarda (notificação persistente) observa desbloqueios.")
                    Row {
                        OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Permitir notificações") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.act { "Concessão via Device Owner: " + graph.facade.setPermissionGranted(Manifest.permission.POST_NOTIFICATIONS, true).describe() } }) { Text("Conceder via DO") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Alarmes exatos") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text("Ignorar otimização de bateria") }
                    }
                }
            },
            { ChecksCard("Verificações", r.checks) },
            {
                InfoCard("Limites") {
                    Paragraph("O Watchdog detecta e alerta localmente; ele NÃO é capaz de impedir a própria remoção em todas as circunstâncias (ex.: reset de fábrica via recovery, remoção do Device Owner por quem tem a credencial).")
                    Spacer(Modifier.height(16.dp))
                }
            },
        )
    }
}
