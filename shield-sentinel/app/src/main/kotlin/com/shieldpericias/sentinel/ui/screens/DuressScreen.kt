package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.core.duress.DuressAction
import com.shieldpericias.sentinel.core.duress.DuressPolicy
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.KeyValue
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.RadioGroup
import com.shieldpericias.sentinel.ui.components.SectionTitle
import com.shieldpericias.sentinel.ui.components.SwitchRow
import com.shieldpericias.sentinel.ui.components.TypedConfirmDialog
import com.shieldpericias.sentinel.ui.components.WarningText

data class DuressData(val hasNormal: Boolean, val hasDuress: Boolean, val policy: DuressPolicy, val dryRun: Boolean, val wipeScope: String)

@Composable
fun DuressScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<DuressData> = viewModel(key = "duress") {
        ScreenViewModel {
            DuressData(graph.duressPins.hasNormalPin(), graph.duressPins.hasDuressPin(), graph.duressPins.policy(),
                graph.autoWipeConfig.load().dryRun, graph.realWipe.availableScope()?.name ?: "indisponível")
        }
    }
    var pin1 by rememberSaveable { mutableStateOf("") }
    var pin2 by rememberSaveable { mutableStateOf("") }
    var dpin1 by rememberSaveable { mutableStateOf("") }
    var dpin2 by rememberSaveable { mutableStateOf("") }
    var ackDialog by rememberSaveable { mutableStateOf(false) }

    ViewModelScreen(vm, snack) { d ->
        fun savePolicy(p: DuressPolicy) = vm.act { graph.duressPins.savePolicy(p); "Política salva" }
        if (ackDialog) {
            TypedConfirmDialog(
                title = "Reconhecer apagamento completo",
                message = "Ao digitar o PIN de emergência o aparelho será APAGADO (factory reset) sem confirmação adicional. Escopo: ${d.wipeScope}. Modo de ensaio atual: ${if (d.dryRun) "LIGADO (simula)" else "DESLIGADO (real)"}.",
                requiredText = "APAGAR",
                onConfirm = { ackDialog = false; savePolicy(d.policy.copy(action = DuressAction.FACTORY_RESET, ownerAcknowledgedFactoryReset = true)) },
                onDismiss = { ackDialog = false },
            )
        }
        ScrollBody(
            {
                InfoCard("Como funciona") {
                    Paragraph("O PIN protege a interface do Shield Sentinel (não é o PIN da tela de bloqueio do Android). O PIN normal abre a administração; o PIN de emergência (duress) executa a política configurada imediatamente, sem confirmação.")
                    WarningText("Sem modo de disfarce. O Sentinel não intercepta a tela de bloqueio nativa (nem via Accessibility Service); o PIN duress só funciona dentro do app.")
                    KeyValue("PIN normal", if (d.hasNormal) "definido" else "não definido")
                    KeyValue("PIN de emergência", if (d.hasDuress) "definido" else "não definido")
                }
            },
            {
                SectionTitle("PIN normal")
                InfoCard {
                    OutlinedTextField(value = pin1, onValueChange = { pin1 = it }, label = { Text("Novo PIN (≥ 4)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pin2, onValueChange = { pin2 = it }, label = { Text("Repetir") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    Button(enabled = pin1.length >= 4 && pin1 == pin2, onClick = {
                        val p = pin1.toCharArray(); pin1 = ""; pin2 = ""
                        vm.act { if (graph.duressPins.setNormalPin(p)) "PIN normal definido" else "PIN inválido (curto ou igual ao de emergência)" }
                    }) { Text("Definir PIN normal") }
                }
            },
            {
                SectionTitle("PIN de emergência (duress)")
                InfoCard {
                    OutlinedTextField(value = dpin1, onValueChange = { dpin1 = it }, label = { Text("PIN de emergência (≠ normal)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = dpin2, onValueChange = { dpin2 = it }, label = { Text("Repetir") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    Row {
                        Button(enabled = d.hasNormal && dpin1.length >= 4 && dpin1 == dpin2, onClick = {
                            val p = dpin1.toCharArray(); dpin1 = ""; dpin2 = ""
                            vm.act { if (graph.duressPins.setDuressPin(p)) "PIN de emergência definido" else "Inválido: curto ou igual ao PIN normal" }
                        }) { Text("Definir") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(enabled = d.hasDuress, onClick = { vm.act { graph.duressPins.clearDuressPin(); "Removido" } }) { Text("Remover") }
                    }
                    if (!d.hasNormal) WarningText("Defina primeiro o PIN normal.")
                }
            },
            {
                SectionTitle("Política de emergência")
                InfoCard {
                    SwitchRow("Política habilitada", d.policy.enabled, enabled = d.hasDuress) { savePolicy(d.policy.copy(enabled = it)) }
                    RadioGroup(listOf(
                        DuressAction.LOCK to "1. Bloqueio",
                        DuressAction.LOCK_AND_REBOOT to "2. Bloqueio e reinicialização (Device Owner)",
                        DuressAction.FACTORY_RESET to "3. Factory Reset completo (opcional; exige reconhecimento)",
                    ), d.policy.action) { a ->
                        if (a == DuressAction.FACTORY_RESET) ackDialog = true else savePolicy(d.policy.copy(action = a, ownerAcknowledgedFactoryReset = false))
                    }
                    KeyValue("Executável agora", d.policy.isExecutable.toString())
                    KeyValue("Apagar eSIM", "não (padrão fixo nesta versão)")
                    Paragraph("Formatação desativada por padrão; só executa com reconhecimento digitado. Em modo de ensaio (Antirroubo) o apagamento é simulado e registrado em auditoria.")
                    Spacer(Modifier.height(16.dp))
                }
            },
        )
    }
}
