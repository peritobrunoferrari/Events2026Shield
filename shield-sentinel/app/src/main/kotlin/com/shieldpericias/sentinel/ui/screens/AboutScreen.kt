package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.KeyValue
import com.shieldpericias.sentinel.ui.components.Paragraph

@Composable
fun AboutScreen() {
    ScrollBody(
        {
            InfoCard("Shield Sentinel") {
                KeyValue("Desenvolvedor", "Shield Perícias")
                KeyValue("Versão", "0.1.0-alpha")
                KeyValue("Plataforma", "Android 12+ (prioridade Android 16/17); homologação inicial: Google Pixel")
                Paragraph("Controlador de políticas (DPC) local, em modo Device Owner, sem servidor, sem telemetria, sem root.")
            }
        },
        {
            InfoCard("Princípios") {
                Paragraph("• Sem root, Magisk, KernelSU, Shizuku ou exploração de vulnerabilidades.")
                Paragraph("• Bootloader bloqueado e Android Verified Boot preservados.")
                Paragraph("• Apenas APIs públicas e oficiais; funciona offline.")
                Paragraph("• Todo recurso exibe seu estado real: ativo, inativo, indisponível ou erro.")
                Paragraph("• Dados mínimos; nada sai do aparelho; backup desativado.")
            }
        },
        {
            InfoCard("Limitações principais") {
                Paragraph("• O nível de segurança é uma síntese das políticas verificadas, não uma garantia contra extração forense.")
                Paragraph("• Camadas USB dependem do suporte do fabricante (canUsbDataSignalingBeDisabled); não equivalem a controle de hardware.")
                Paragraph("• O Watchdog alerta, mas não impede a própria remoção em todos os cenários.")
                Paragraph("• O PIN duress funciona apenas dentro do app, não na tela de bloqueio nativa.")
                Paragraph("• Lista completa em docs/LIMITATIONS.md.")
            }
        },
        {
            InfoCard("Terceiros e referências") {
                Paragraph("• ZXing (Apache-2.0) — geração do QR de provisionamento.")
                Paragraph("• Projetos x13a/Sentry, Wasted e Duress (GPL-3.0) foram estudados apenas como referência conceitual; nenhum código foi copiado.")
                Paragraph("• GrapheneOS (features) como referência de requisitos; nenhuma equivalência é alegada.")
                Spacer(Modifier.height(16.dp))
            }
        },
    )
}
