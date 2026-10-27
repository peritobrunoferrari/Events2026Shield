package com.shieldpericias.sentinel.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.usb.UsbImpact
import com.shieldpericias.sentinel.core.usb.UsbProfile
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.ChecksCard
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.RadioGroup
import com.shieldpericias.sentinel.ui.components.SectionTitle
import com.shieldpericias.sentinel.ui.components.SimpleConfirmDialog
import com.shieldpericias.sentinel.ui.components.WarningText
import com.shieldpericias.sentinel.ui.components.describe
import com.shieldpericias.sentinel.usbsecurity.UsbSnapshot

data class UsbData(val snapshot: UsbSnapshot, val checks: List<PolicyCheck>, val isDeviceOwner: Boolean)

private fun UsbImpact.ptBr() = when (this) {
    UsbImpact.FILE_TRANSFER_BLOCKED -> "Transferência de arquivos (MTP/PTP) bloqueada"
    UsbImpact.OTG_STORAGE_BLOCKED -> "Pendrives/armazenamento OTG bloqueados"
    UsbImpact.ADB_BLOCKED -> "ADB por cabo deixa de funcionar"
    UsbImpact.ANDROID_AUTO_USB_BLOCKED -> "Android Auto por cabo deixa de funcionar"
    UsbImpact.OTG_ACCESSORIES_BLOCKED -> "Acessórios USB (teclado, áudio, leitores) deixam de funcionar"
}

private fun UsbProfile.ptBr() = when (this) {
    UsbProfile.COMPATIBILITY -> "Compatibilidade — prioriza proteção nativa; sem políticas DO"
    UsbProfile.ELEVATED -> "Proteção elevada — restringe transferência de arquivos e OTG"
    UsbProfile.MAXIMUM -> "Proteção máxima — desabilita sinalização de dados USB (somente carga)"
}

@Composable
fun UsbScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<UsbData> = viewModel(key = "usb") {
        ScreenViewModel { val s = graph.usb.snapshot(); UsbData(s, graph.usb.checks(s), graph.facade.mode().isDeviceOwner) }
    }
    val context = LocalContext.current
    var selected by remember { mutableStateOf<UsbProfile?>(null) }
    var confirmMax by rememberSaveable { mutableStateOf(false) }

    ViewModelScreen(vm, snack) { d ->
        val current = selected ?: d.snapshot.selectedProfile ?: UsbProfile.COMPATIBILITY
        if (confirmMax) {
            SimpleConfirmDialog(
                title = "Aplicar proteção máxima?",
                message = "A sinalização de dados USB será desabilitada. ADB por cabo, Android Auto e acessórios deixam de funcionar até a reversão pelo app (ou perda do Device Owner). Mantenha um caminho de recuperação independente de USB (ver Guia de Recuperação).",
                onConfirm = { confirmMax = false; vm.act { graph.usb.applyProfile(UsbProfile.MAXIMUM).joinToString { "${it.first}: ${it.second.describe()}" } } },
                onDismiss = { confirmMax = false },
            )
        }
        ScrollBody(
            { ChecksCard("Estado real por camada", d.checks) },
            {
                SectionTitle("Camada A — proteção nativa")
                InfoCard {
                    Paragraph("O Modo de Proteção Avançada (Android 16+) restringe sinalização USB, sideload e outras superfícies. A API pública só permite consultar o estado; a ativação é feita pelo usuário nas Configurações.")
                    OutlinedButton(onClick = { runCatching { context.startActivity(graph.advancedProtection.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Abrir Configurações de segurança") }
                }
            },
            {
                SectionTitle("Perfil de proteção USB (camadas B e C)")
                InfoCard {
                    if (!d.isDeviceOwner) WarningText("As camadas B e C exigem Device Owner. Neste modo apenas a camada A (nativa) está disponível.")
                    RadioGroup(UsbProfile.entries.map { it to it.ptBr() }, current, enabled = d.isDeviceOwner) { selected = it }
                    Spacer(Modifier.height(6.dp))
                    Text("Impactos previstos:")
                    val impacts = current.impacts
                    if (impacts.isEmpty()) Paragraph("• Nenhum impacto em acessórios.") else impacts.forEach { Paragraph("• ${it.ptBr()}") }
                    if (current.disableUsbDataSignaling && !d.snapshot.dataSignalingSupported) WarningText("Este aparelho/modo não suporta desabilitar a sinalização USB; o perfil máximo aplicará apenas as camadas C e OTG.")
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(enabled = d.isDeviceOwner, onClick = {
                            if (current == UsbProfile.MAXIMUM) confirmMax = true
                            else vm.act { graph.usb.applyProfile(current).joinToString { "${it.first}: ${it.second.describe()}" } }
                        }) { Text("Aplicar perfil") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(enabled = d.isDeviceOwner, onClick = { selected = null; vm.act { graph.usb.revertAll().joinToString { "${it.first}: ${it.second.describe()}" } } }) { Text("Reverter tudo") }
                    }
                }
            },
            {
                SectionTitle("Limites declarados")
                InfoCard {
                    Paragraph("• DISALLOW_USB_FILE_TRANSFER bloqueia MTP/PTP, mas não equivale ao bloqueio completo de dados USB.")
                    Paragraph("• setUsbDataSignalingEnabled(false) depende do suporte do aparelho (canUsbDataSignalingBeDisabled). Em dispositivos sem suporte, o estado é exibido como INDISPONÍVEL.")
                    Paragraph("• Nenhuma destas camadas equivale ao controle de hardware de porta USB do GrapheneOS; o Sentinel usa apenas APIs públicas do Android.")
                    Paragraph("• Reversão: 'Reverter tudo' remove todas as políticas USB do Sentinel; a Proteção Avançada nativa continua sob controle do usuário.")
                    Spacer(Modifier.height(16.dp))
                }
            },
        )
    }
}
