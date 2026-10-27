package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.autoreboot.AutoRebootConfig
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.inactivity.InactivityLedgerState
import com.shieldpericias.sentinel.core.inactivity.RebootPlanner
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
import com.shieldpericias.sentinel.ui.components.WarningText

data class RebootData(val config: AutoRebootConfig, val check: PolicyCheck, val plan: RebootPlanner.Plan, val ledger: InactivityLedgerState, val isDeviceOwner: Boolean, val lastUnlockVerification: String)

@Composable
fun AutoRebootScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<RebootData> = viewModel(key = "reboot") {
        ScreenViewModel {
            val cfg = graph.autoRebootConfig.load()
            val ledger = graph.ledgerStore.load()
            RebootData(cfg, graph.rebootScheduler.check(), RebootPlanner.plan(ledger, graph.clock.snapshot(), cfg.intervalMs), ledger, graph.facade.mode().isDeviceOwner, graph.unlockVerifier.lastResult)
        }
    }
    ViewModelScreen(vm, snack) { d ->
        fun save(c: AutoRebootConfig) = vm.act { graph.autoRebootConfig.save(c); graph.rebootScheduler.rearm(); "Configuração salva" }
        ScrollBody(
            { InfoCard("Estado") { CheckRow(d.check) } },
            {
                InfoCard("Configuração") {
                    if (!d.isDeviceOwner) WarningText("DevicePolicyManager.reboot() exige Device Owner. A configuração pode ser salva, mas nada será executado.")
                    SwitchRow("Reinicialização automática", d.config.enabled, enabled = d.isDeviceOwner) { save(d.config.copy(enabled = it)) }
                    Text("Intervalo sem desbloqueio:")
                    RadioGroup(RebootPlanner.ALLOWED_INTERVALS_MS.map { it to fmtDuration(it) }, d.config.intervalMs, enabled = d.isDeviceOwner) { save(d.config.copy(intervalMs = it)) }
                    SwitchRow("Modo de ensaio (não reinicia; só registra)", d.config.dryRun, description = "Use durante a homologação.") { save(d.config.copy(dryRun = it)) }
                }
            },
            {
                SectionTitle("Livro-razão de desbloqueio")
                InfoCard {
                    KeyValue("Último desbloqueio verificado", fmtDate(d.ledger.lastVerifiedUnlockEpochMs))
                    KeyValue("Boot do desbloqueio / boot atual", "${d.ledger.lastVerifiedUnlockBootCount ?: "—"} / ${graph.clock.snapshot().bootCount}")
                    KeyValue("Plano", d.plan.toString())
                    KeyValue("Última verificação de desbloqueio", d.lastUnlockVerification)
                    Paragraph("Só desbloqueios verificados (bloqueio seguro + chave auth-bound do Keystore aceita) reiniciam o timer. O Watchdog mantém o serviço de guarda em primeiro plano para observar o evento USER_PRESENT.")
                }
            },
            {
                SectionTitle("O que isso faz (e não faz)")
                InfoCard {
                    Paragraph("• Bloquear a tela NÃO equivale a BFU: as chaves de criptografia do usuário continuam em memória (AFU).")
                    Paragraph("• Reiniciar retorna o aparelho ao estado anterior ao primeiro desbloqueio (BFU) quando a criptografia baseada em arquivos funciona conforme esperado — os dados do usuário ficam indisponíveis até a credencial.")
                    Paragraph("• O Android pode adiar alarmes inexatos (Doze) e restringir execução em segundo plano; com permissão de alarme exato o horário é respeitado.")
                    Paragraph("• Após reiniciar, o timer só é rearmado no próximo desbloqueio verificado (o aparelho já está em BFU).")
                    Paragraph("• reboot() falha durante uma chamada em andamento; o Sentinel tenta novamente em 10 minutos.")
                    Spacer(Modifier.height(16.dp))
                }
            },
        )
    }
}
