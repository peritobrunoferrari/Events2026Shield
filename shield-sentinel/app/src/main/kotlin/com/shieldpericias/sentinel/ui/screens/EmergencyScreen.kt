package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.core.emergency.EmergencyActionId
import com.shieldpericias.sentinel.core.emergency.EmergencyArming
import com.shieldpericias.sentinel.emergency.EngineResult
import com.shieldpericias.sentinel.emergency.TriggerSource
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.SectionTitle
import com.shieldpericias.sentinel.ui.components.SimpleConfirmDialog
import com.shieldpericias.sentinel.ui.components.SwitchRow
import com.shieldpericias.sentinel.ui.components.WarningText
import com.shieldpericias.sentinel.ui.components.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun EmergencyActionId.ptBr() = when (this) {
    EmergencyActionId.LOCK_NOW -> "Bloquear agora (lockNow)"
    EmergencyActionId.USB_PROTECTION -> "Proteção USB máxima"
    EmergencyActionId.REBOOT -> "Reiniciar (retorna a BFU)"
    EmergencyActionId.SECURITY_STATUS -> "Relatório de status de segurança"
}

internal fun EngineResult.describe(): String = when (this) {
    EngineResult.NotArmed -> "Emergência não armada: configure antes."
    EngineResult.NeedsConfirmation -> "Confirmação necessária."
    is EngineResult.Executed -> results.joinToString("\n") { "${it.id.ptBr()}: ${it.result.describe()} — ${it.message}" }
}

data class EmergencyData(val arming: EmergencyArming, val allowFromLockScreen: Boolean, val isDeviceOwner: Boolean)

@Composable
fun EmergencyScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<EmergencyData> = viewModel(key = "emergency") {
        ScreenViewModel { EmergencyData(graph.emergencyConfig.load(), graph.emergencyConfig.allowFromLockScreen, graph.facade.mode().isDeviceOwner) }
    }
    var confirm by rememberSaveable { mutableStateOf(false) }
    ViewModelScreen(vm, snack) { d ->
        fun save(a: EmergencyArming) = vm.act { graph.emergencyConfig.save(a); "Salvo" }
        if (confirm) {
            SimpleConfirmDialog("Acionar emergência?", "Ações: ${d.arming.actions.joinToString { it.ptBr() }}",
                onConfirm = { confirm = false; vm.act { graph.emergency.trigger(TriggerSource.APP_BUTTON, confirmed = true).describe() } },
                onDismiss = { confirm = false })
        }
        ScrollBody(
            {
                InfoCard("Configuração (proteção contra acionamento acidental)") {
                    SwitchRow("Armado", d.arming.armed, description = "Nenhum gatilho funciona desarmado.") { save(d.arming.copy(armed = it)) }
                    SwitchRow("Exigir confirmação", d.arming.requireConfirmation, description = "Diálogo antes de executar (recomendado).") { save(d.arming.copy(requireConfirmation = it)) }
                    SwitchRow("Permitir do Tile com a tela bloqueada", d.allowFromLockScreen, description = "Executa direto sem desbloquear; só com confirmação desligada.") { vm.act { graph.emergencyConfig.allowFromLockScreen = it; "Salvo" } }
                    Text("Ações incluídas:")
                    EmergencyActionId.entries.forEach { id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = id in d.arming.actions, onCheckedChange = { on -> save(d.arming.copy(actions = if (on) d.arming.actions + id else d.arming.actions - id)) })
                            Text(id.ptBr())
                        }
                    }
                    if (!d.isDeviceOwner) WarningText("Sem Device Owner: 'Reiniciar' e 'Proteção USB' ficarão indisponíveis; 'Bloquear agora' exige ao menos Device Admin ativo.")
                }
            },
            {
                SectionTitle("Gatilhos")
                InfoCard {
                    Paragraph("• Botão abaixo (app).")
                    Paragraph("• Atalho de emergência: toque longo no ícone do Sentinel na tela inicial › Emergência.")
                    Paragraph("• Quick Settings Tile 'Sentinel: Emergência' (adicione no painel de configurações rápidas).")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        if (d.arming.requireConfirmation) confirm = true
                        else vm.act { graph.emergency.trigger(TriggerSource.APP_BUTTON, confirmed = true).describe() }
                    }, enabled = d.arming.armed, modifier = Modifier.fillMaxWidth()) { Text("ACIONAR EMERGÊNCIA") }
                    Spacer(Modifier.height(16.dp))
                }
            },
        )
    }
}

/** Tela usada pela EmergencyActivity (atalho/tile). */
@Composable
fun EmergencyConfirmScreen(graph: SentinelGraph, source: TriggerSource, onClose: () -> Unit) {
    var result by remember { mutableStateOf<String?>(null) }
    val cfg = remember { graph.emergency.config() }
    // Sem confirmação configurada: executa imediatamente ao abrir. Caso contrário aguarda o botão.
    var running by remember { mutableStateOf(cfg.armed && !cfg.requireConfirmation) }

    LaunchedEffect(running) {
        if (running && result == null) {
            result = withContext(Dispatchers.IO) { graph.emergency.trigger(source, confirmed = true).describe() }
            running = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Shield Sentinel — Emergência", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        val r = result
        when {
            !cfg.armed -> Text("Emergência não configurada. Abra o app e arme as ações desejadas.")
            r != null -> Text(r)
            running -> Text("Executando…")
            else -> {
                Text("Ações: ${cfg.actions.joinToString { it.ptBr() }}")
                Spacer(Modifier.height(16.dp))
                Button(modifier = Modifier.fillMaxWidth(), onClick = { running = true }) { Text("CONFIRMAR EMERGÊNCIA") }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onClose) { Text("Fechar") }
    }
}
