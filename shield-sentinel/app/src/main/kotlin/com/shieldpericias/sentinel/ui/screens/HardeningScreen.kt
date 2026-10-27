package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.core.FeatureState
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.hardening.HardeningPolicy
import com.shieldpericias.sentinel.ui.ScreenViewModel
import com.shieldpericias.sentinel.ui.ScrollBody
import com.shieldpericias.sentinel.ui.ViewModelScreen
import com.shieldpericias.sentinel.ui.components.ChecksCard
import com.shieldpericias.sentinel.ui.components.InfoCard
import com.shieldpericias.sentinel.ui.components.Paragraph
import com.shieldpericias.sentinel.ui.components.SectionTitle
import com.shieldpericias.sentinel.ui.components.StateBadge
import com.shieldpericias.sentinel.ui.components.SwitchRow
import com.shieldpericias.sentinel.ui.components.WarningText
import com.shieldpericias.sentinel.ui.components.describe

data class HardeningData(val states: Map<HardeningPolicy, FeatureState>, val insecure: List<PolicyCheck>)

@Composable
fun HardeningScreen(graph: SentinelGraph, snack: SnackbarHostState) {
    val vm: ScreenViewModel<HardeningData> = viewModel(key = "hardening") {
        ScreenViewModel { HardeningData(HardeningPolicy.entries.associateWith { graph.hardening.state(it) }, graph.hardening.insecureSettings()) }
    }
    ViewModelScreen(vm, snack) { d ->
        val blocks = buildList<@Composable () -> Unit> {
            add { ChecksCard("Alertas de configurações inseguras e atualizações", d.insecure) }
            add {
                SectionTitle("Políticas (aplicadas quando o modo administrativo permitir)")
                Paragraph("O Sentinel não desativa atualizações de segurança, não modifica SELinux e não substitui o sistema. A interface do próprio app já é protegida contra captura de tela.")
            }
            HardeningPolicy.entries.forEach { p ->
                add {
                    val st = d.states[p] ?: FeatureState.Error("sem leitura")
                    InfoCard {
                        StateBadge(st)
                        SwitchRow(p.title, checked = st.isActive, enabled = st !is FeatureState.Unavailable, description = p.description) { on ->
                            vm.act { "${p.title}: " + graph.hardening.apply(p, on).describe() }
                        }
                        p.riskNote?.let { WarningText(it) }
                    }
                }
            }
            add { Spacer(Modifier.height(16.dp)) }
        }
        ScrollBody(blocks)
    }
}
