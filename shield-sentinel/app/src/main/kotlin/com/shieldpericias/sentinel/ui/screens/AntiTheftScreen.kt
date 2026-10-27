package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.shieldpericias.sentinel.antitheft.WipeScope
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.inactivity.AutoWipeConfig
import com.shieldpericias.sentinel.core.inactivity.EvidencePolicy
import com.shieldpericias.sentinel.core.inactivity.InactivityLedgerState
import com.shieldpericias.sentinel.core.inactivity.InactivityVerdict
import com.shieldpericias.sentinel.core.remote.RemoteCommand
import com.shieldpericias.sentinel.remotewipe.RemoteOutcome
import com.shieldpericias.sentinel.remotewipe.RemoteWipeConfig
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.CheckRow
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.KeyValue
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.RadioGroup
import com.shieldpericias.sentinel.ui.components.SectionTitle
import com.shieldpericias.sentinel.ui.components.SwitchRow
import com.shieldpericias.sentinel.ui.components.TypedConfirmDialog
import com.shieldpericias.sentinel.ui.components.WarningText
import com.shieldpericias.sentinel.ui.components.describe

data class AntiTheftData(
    val config: AutoWipeConfig,
    val check: PolicyCheck,
    val verdict: InactivityVerdict,
    val ledger: InactivityLedgerState,
    val scope: WipeScope?,
    val remote: RemoteWipeConfig,
    val deviceId: String,
    val adminKeys: List<String>,
    val pending: List<RemoteCommand>,
)

@Composable
fun AntiTheftScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<AntiTheftData> = viewModel(key = "antitheft") {
        ScreenViewModel {
            val cfg = graph.autoWipeConfig.load()
            val eval = graph.autoWipe.evaluate(execute = false)
            AntiTheftData(cfg, graph.autoWipe.check(), eval.verdict, graph.ledgerStore.load(), graph.realWipe.availableScope(),
                graph.remoteWipeConfig.load(), graph.deviceIdentity.id(), graph.trustAnchors.keyIds(), graph.remoteProcessor.pendingCommands())
        }
    }
    var confirmReal by rememberSaveable { mutableStateOf(false) }
    var keyId by rememberSaveable { mutableStateOf("") }
    var keySpki by rememberSaveable { mutableStateOf("") }
    var command by rememberSaveable { mutableStateOf("") }

    ViewModelScreen(vm, snack) { d ->
        fun save(c: AutoWipeConfig) = vm.act { graph.autoWipeConfig.save(c); graph.antiTheft.schedule(); "Configuração salva" }
        if (confirmReal) {
            TypedConfirmDialog(
                title = "Desativar modo de ensaio",
                message = "Com o ensaio desligado, ao atingir o prazo comprovado o Sentinel executará o APAGAMENTO REAL (${d.scope ?: "indisponível"}). Certifique-se de ter backups e de entender as condições de prova.",
                requiredText = "APAGAR",
                onConfirm = { confirmReal = false; save(d.config.copy(dryRun = false)) },
                onDismiss = { confirmReal = false },
            )
        }
        ScrollBody(
            { InfoCard("Estado") { CheckRow(d.check) } },
            {
                InfoCard("Auto-Wipe por inatividade") {
                    if (d.scope == null) WarningText("Apagamento indisponível: exige Device Owner (aparelho inteiro) ou Profile Owner (apenas o perfil).")
                    else KeyValue("Escopo do apagamento", if (d.scope == WipeScope.DEVICE) "APARELHO COMPLETO (factory reset)" else "SOMENTE O PERFIL GERENCIADO")
                    SwitchRow("Ativar Auto-Wipe", d.config.enabled, enabled = d.scope != null, description = "Desativado por padrão.") { save(d.config.copy(enabled = it)) }
                    Text("Prazo sem desbloqueio verificado:")
                    RadioGroup(AutoWipeConfig.ALLOWED_THRESHOLDS_MS.map { it to "${it / 86_400_000} dias" }, d.config.thresholdMs) { save(d.config.copy(thresholdMs = it)) }
                    Text("Prova de inatividade:")
                    RadioGroup(listOf(
                        EvidencePolicy.STRICT to "Estrita — relógio E tempo ligado acumulado ≥ prazo (recomendado)",
                        EvidencePolicy.WALL_CLOCK_ONLY to "Somente relógio — vulnerável a alteração de data",
                    ), d.config.evidencePolicy) { save(d.config.copy(evidencePolicy = it)) }
                    SwitchRow("Modo de ensaio (simula o apagamento)", d.config.dryRun, description = "Mantenha ligado até a homologação. O apagamento real passa por confirmação digitada.") { on ->
                        if (on) save(d.config.copy(dryRun = true)) else confirmReal = true
                    }
                    Paragraph("eSIM nunca é apagada por padrão (sem WIPE_EUICC).")
                }
            },
            {
                SectionTitle("Avaliação atual")
                InfoCard {
                    KeyValue("Decisão", d.verdict.decision.name)
                    KeyValue("Tempo de relógio desde o desbloqueio", fmtDuration(d.verdict.wallClockElapsedMs))
                    KeyValue("Tempo ligado acumulado", fmtDuration(d.verdict.poweredElapsedMs))
                    KeyValue("Restante", fmtDuration(d.verdict.remainingMs))
                    KeyValue("Motivos", d.verdict.reasons.joinToString())
                    KeyValue("Anomalia de relógio", d.ledger.clockAnomalyDetected.toString())
                    KeyValue("Último desbloqueio verificado", fmtDate(d.ledger.lastVerifiedUnlockEpochMs))
                    Paragraph("O contador só zera com desbloqueio verificado. As reinicializações automáticas NÃO zeram o contador. Quando não é possível comprovar o período (relógio alterado, sem baseline), o Sentinel NÃO apaga.")
                }
            },
            {
                SectionTitle("Remote Wipe (opcional, desativado por padrão)")
                InfoCard {
                    SwitchRow("Aceitar comandos remotos assinados", d.remote.enabled) { vm.act { graph.remoteWipeConfig.save(d.remote.copy(enabled = it)); "Remote Wipe ${if (it) "ativado" else "desativado"}" } }
                    SwitchRow("Exigir confirmação administrativa local para WIPE", d.remote.requireAdminConfirmation) { vm.act { graph.remoteWipeConfig.save(d.remote.copy(requireAdminConfirmation = it)); "Salvo" } }
                    KeyValue("ID do dispositivo (para o comando)", d.deviceId)
                    KeyValue("Chaves administrativas", if (d.adminKeys.isEmpty()) "nenhuma" else d.adminKeys.joinToString())
                    Paragraph("Requisitos do protocolo: assinatura ECDSA P-256, vínculo com o ID do dispositivo, validade temporal (≤ 24 h), contador e ID únicos (anti-replay), revogação por chave. SMS, broadcasts, webhooks sem autenticação e número de telefone NÃO são aceitos como autorização. O transporte é um módulo opcional; aqui o comando pode ser colado manualmente.")
                    OutlinedTextField(value = keyId, onValueChange = { keyId = it }, label = { Text("ID da chave administrativa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = keySpki, onValueChange = { keySpki = it }, label = { Text("Chave pública EC (SubjectPublicKeyInfo, base64)") }, modifier = Modifier.fillMaxWidth())
                    Row {
                        Button(enabled = keyId.isNotBlank() && keySpki.isNotBlank(), onClick = { vm.act { if (graph.trustAnchors.add(keyId.trim(), keySpki)) "Chave registrada" else "Chave inválida (esperado EC P-256 X.509)" } }) { Text("Registrar chave") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(enabled = keyId.isNotBlank(), onClick = { vm.act { graph.trustAnchors.revoke(keyId.trim()); "Chave revogada" } }) { Text("Revogar") }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("Comando assinado (payload.assinatura)") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = command.isNotBlank(), onClick = {
                        vm.act {
                            when (val r = graph.remoteProcessor.submit(command.trim())) {
                                is RemoteOutcome.Rejected -> "Rejeitado: ${r.reason} ${r.detail ?: ""}"
                                is RemoteOutcome.PendingConfirmation -> "Comando ${r.command.action} verificado; aguarda confirmação administrativa"
                                is RemoteOutcome.Executed -> "Executado ${r.command.action}: ${r.result.describe()}${if (r.simulated) " (ensaio)" else ""}"
                            }
                        }
                    }) { Text("Processar comando") }
                    d.pending.forEach { c ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("Pendente: ${c.action} (${c.commandId.take(8)}…)", modifier = Modifier.weight(1f))
                            Button(onClick = { vm.act { graph.remoteProcessor.confirm(c.commandId)?.let { "Executado" } ?: "não encontrado" } }) { Text("Confirmar") }
                            Spacer(Modifier.width(4.dp))
                            OutlinedButton(onClick = { vm.act { graph.remoteProcessor.discard(c.commandId); "Descartado" } }) { Text("Descartar") }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            },
        )
    }
}
