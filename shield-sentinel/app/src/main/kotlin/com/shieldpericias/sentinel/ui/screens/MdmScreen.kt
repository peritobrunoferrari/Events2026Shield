package com.shieldpericias.sentinel.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.core.FeatureState
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.coresecurity.AdminStatus
import com.shieldpericias.sentinel.coresecurity.Capability
import com.shieldpericias.sentinel.coresecurity.DeviceInfo
import com.shieldpericias.sentinel.deviceowner.ProvisioningHelper
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.CheckRow
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.KeyValue
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.SectionTitle
import com.shieldpericias.sentinel.ui.components.StateBadge
import com.shieldpericias.sentinel.ui.components.TypedConfirmDialog
import com.shieldpericias.sentinel.ui.components.WarningText
import com.shieldpericias.sentinel.ui.components.describe

data class MdmData(
    val device: DeviceInfo,
    val status: AdminStatus,
    val capabilities: List<Capability>,
    val prerequisites: List<ProvisioningHelper.Prerequisite>,
    val adbCommand: String,
    val component: String,
    val signatureChecksum: String?,
)

private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = if (matrix.get(x, y)) -0x1000000 else -0x1
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
}

@Composable
fun MdmScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<MdmData> = viewModel(key = "mdm") {
        ScreenViewModel {
            val status = graph.facade.status()
            MdmData(DeviceInfo.collect(), status, graph.capabilities.detect(status), graph.provisioning.prerequisites(graph.facade),
                graph.provisioning.adbCommand(), graph.provisioning.componentFlattened, graph.provisioning.signatureChecksum())
        }
    }
    val context = LocalContext.current
    var apkUrl by rememberSaveable { mutableStateOf("https://exemplo.invalido/shield-sentinel-debug.apk") }
    var qr by remember { mutableStateOf<String?>(null) }
    var showClearDo by rememberSaveable { mutableStateOf(false) }

    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    ViewModelScreen(vm, snack) { d ->
        if (showClearDo) {
            TypedConfirmDialog(
                title = "Remover Device Owner",
                message = "Isso remove TODAS as políticas do Sentinel e devolve o aparelho ao estado não gerenciado. Só é possível refazer o provisionamento com o aparelho sem contas (ou após reset).",
                requiredText = "REMOVER",
                onConfirm = { showClearDo = false; vm.act { "clearDeviceOwner: " + graph.facade.clearDeviceOwner().describe() } },
                onDismiss = { showClearDo = false },
            )
        }
        ScrollBody(
            {
                InfoCard("Estado administrativo") {
                    KeyValue("Modo", d.status.mode.name)
                    KeyValue("Device Owner", d.status.isDeviceOwner.toString())
                    KeyValue("Profile Owner", d.status.isProfileOwner.toString())
                    KeyValue("Device Admin ativo", d.status.isDeviceAdminActive.toString())
                    KeyValue("Administradores ativos", d.status.activeAdminCount.toString())
                    KeyValue("Fabricante/modelo", "${d.device.manufacturer} ${d.device.model} (${d.device.device})")
                    KeyValue("Android / API", "${d.device.androidVersion} / ${d.device.sdkInt}")
                    KeyValue("Componente DPC", d.component)
                }
            },
            {
                SectionTitle("Capacidades detectadas")
                InfoCard { d.capabilities.forEach { c -> CheckRow(PolicyCheck(c.id, c.title, c.state, detail = c.note)) } }
            },
            {
                SectionTitle("Provisionamento Device Owner")
                InfoCard("Pré-requisitos") {
                    d.prerequisites.forEach { p ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            StateBadge(when (p.satisfied) { true -> FeatureState.Active(); false -> FeatureState.Inactive(); null -> FeatureState.Unavailable("não verificável pelo app") })
                        }
                        Text(p.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    WarningText("Aviso: tornar um app Device Owner exige aparelho sem contas. Em aparelhos já configurados, isso pode exigir remover contas ou restaurar o aparelho (apaga tudo). O Sentinel NUNCA executa reset para se provisionar.")
                }
            },
            {
                InfoCard("Via ADB (desenvolvimento/homologação)") {
                    Text(d.adbCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { copy("adb", d.adbCommand) }) { Text("Copiar comando") }
                    Paragraph("Requer depuração USB ativa e nenhuma conta no aparelho. Documentação completa em docs/PROVISIONING.md.")
                }
            },
            {
                InfoCard("Via QR Code (assistente de configuração inicial)") {
                    Paragraph("Após o reset, toque 6 vezes na tela de boas-vindas, conecte ao Wi-Fi e leia o QR. O APK precisa estar hospedado em HTTPS no endereço abaixo; o checksum de assinatura é incluído automaticamente.")
                    OutlinedTextField(value = apkUrl, onValueChange = { apkUrl = it }, label = { Text("URL HTTPS do APK") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    KeyValue("Checksum de assinatura", d.signatureChecksum ?: "indisponível")
                    Row {
                        Button(onClick = { qr = graph.provisioning.qrPayload(apkUrl) }) { Text("Gerar QR") }
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(onClick = { qr?.let { copy("qr", it) } }, enabled = qr != null) { Text("Copiar JSON") }
                    }
                    qr?.let { payload ->
                        val bmp = remember(payload) { runCatching { qrBitmap(payload) }.getOrNull() }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            bmp?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "QR de provisionamento", modifier = Modifier.size(260.dp)) }
                            Text(payload, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            {
                SectionTitle("Recuperação administrativa")
                InfoCard {
                    Paragraph("Remove o Sentinel como Device Owner (reversível apenas por novo provisionamento). Antes, reverta as políticas USB e hardening para não deixar restrições órfãs.")
                    OutlinedButton(onClick = { showClearDo = true }, enabled = d.status.isDeviceOwner) { Text("Remover Device Owner…") }
                    Spacer(Modifier.height(16.dp))
                }
            },
        )
    }
}
