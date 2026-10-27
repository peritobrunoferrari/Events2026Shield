package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.audit.AuditEvent
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.KeyValue
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.SimpleConfirmDialog

data class AuditData(val events: List<AuditEvent>, val chainBreak: Int)

@Composable
fun AuditScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<AuditData> = viewModel(key = "audit") { ScreenViewModel { AuditData(graph.audit.readAll(300), graph.audit.verifyChain()) } }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    ViewModelScreen(vm, snack) { d ->
        if (confirmClear) SimpleConfirmDialog("Limpar auditoria?", "Os registros locais serão apagados.", onConfirm = { confirmClear = false; vm.act { graph.audit.clear(); "Auditoria limpa" } }, onDismiss = { confirmClear = false })
        val blocks = buildList<@Composable () -> Unit> {
            add {
                InfoCard("Registro mínimo de eventos") {
                    KeyValue("Eventos (mais recentes)", d.events.size.toString())
                    KeyValue("Cadeia de hashes", if (d.chainBreak < 0) "íntegra" else "QUEBRA no índice ${d.chainBreak}")
                    Paragraph("Somente eventos de segurança, sem dados pessoais, sem PINs. Nada é enviado para fora do aparelho. Eventos em BFU são registrados com tipo e instante e mesclados após o desbloqueio.")
                    Row {
                        Button(onClick = { vm.refresh() }) { Text("Atualizar") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { confirmClear = true }) { Text("Limpar") }
                    }
                }
            }
            d.events.forEach { e ->
                add {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${fmtDate(e.atEpochMs)}  ${e.type}", style = MaterialTheme.typography.bodyMedium)
                        e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(e.hash.take(16) + "…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider()
                    }
                }
            }
            add { Spacer(Modifier.height(16.dp)) }
        }
        ScrollBody(blocks)
    }
}
