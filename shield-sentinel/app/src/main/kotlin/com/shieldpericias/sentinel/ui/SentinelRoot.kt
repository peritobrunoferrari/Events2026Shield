package com.shieldpericias.sentinel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.ui.screens.AboutScreen
import com.shieldpericias.sentinel.ui.screens.AntiTheftScreen
import com.shieldpericias.sentinel.ui.screens.AuditScreen
import com.shieldpericias.sentinel.ui.screens.AutoRebootScreen
import com.shieldpericias.sentinel.ui.screens.DashboardScreen
import com.shieldpericias.sentinel.ui.screens.DiagnosticsScreen
import com.shieldpericias.sentinel.ui.screens.DuressScreen
import com.shieldpericias.sentinel.ui.screens.EmergencyScreen
import com.shieldpericias.sentinel.ui.screens.HardeningScreen
import com.shieldpericias.sentinel.ui.screens.MdmScreen
import com.shieldpericias.sentinel.ui.screens.PinGateScreen
import com.shieldpericias.sentinel.ui.screens.UsbScreen
import com.shieldpericias.sentinel.ui.screens.WatchdogScreen

enum class Route(val path: String, val title: String) {
    DASHBOARD("dashboard", "Dashboard de Segurança"),
    MDM("mdm", "MDM Local"),
    USB("usb", "Proteção USB"),
    REBOOT("reboot", "Reinicialização Automática"),
    WATCHDOG("watchdog", "Security Watchdog"),
    HARDENING("hardening", "Hardening"),
    ANTITHEFT("antitheft", "Proteção Antirroubo"),
    EMERGENCY("emergency", "Emergência"),
    DURESS("duress", "Duress PIN"),
    AUDIT("audit", "Auditoria"),
    DIAGNOSTICS("diagnostics", "Diagnóstico"),
    ABOUT("about", "Sobre"),
}

@Composable
fun SentinelRoot(graph: SentinelGraph, requestedRoute: Route? = null) {
    var unlocked by remember { mutableStateOf(!graph.duressPins.hasNormalPin()) }
    if (!unlocked) {
        PinGateScreen(graph = graph, onUnlocked = { unlocked = true })
        return
    }
    val nav = rememberNavController()
    // Navegação por intent (builds debug): cada nova rota pedida é empilhada sobre o Dashboard.
    LaunchedEffect(requestedRoute) {
        if (requestedRoute != null && requestedRoute != Route.DASHBOARD) {
            nav.navigate(requestedRoute.path) { launchSingleTop = true; popUpTo(Route.DASHBOARD.path) }
        } else if (requestedRoute == Route.DASHBOARD) {
            nav.popBackStack(Route.DASHBOARD.path, inclusive = false)
        }
    }
    NavHost(navController = nav, startDestination = Route.DASHBOARD.path) {
        composable(Route.DASHBOARD.path) { ScreenScaffold(Route.DASHBOARD, nav, root = true) { snack -> DashboardScreen(graph, nav, snack) } }
        composable(Route.MDM.path) { ScreenScaffold(Route.MDM, nav) { snack -> MdmScreen(graph, snack) } }
        composable(Route.USB.path) { ScreenScaffold(Route.USB, nav) { snack -> UsbScreen(graph, snack) } }
        composable(Route.REBOOT.path) { ScreenScaffold(Route.REBOOT, nav) { snack -> AutoRebootScreen(graph, snack) } }
        composable(Route.WATCHDOG.path) { ScreenScaffold(Route.WATCHDOG, nav) { snack -> WatchdogScreen(graph, snack) } }
        composable(Route.HARDENING.path) { ScreenScaffold(Route.HARDENING, nav) { snack -> HardeningScreen(graph, snack) } }
        composable(Route.ANTITHEFT.path) { ScreenScaffold(Route.ANTITHEFT, nav) { snack -> AntiTheftScreen(graph, snack) } }
        composable(Route.EMERGENCY.path) { ScreenScaffold(Route.EMERGENCY, nav) { snack -> EmergencyScreen(graph, snack) } }
        composable(Route.DURESS.path) { ScreenScaffold(Route.DURESS, nav) { snack -> DuressScreen(graph, snack) } }
        composable(Route.AUDIT.path) { ScreenScaffold(Route.AUDIT, nav) { snack -> AuditScreen(graph, snack) } }
        composable(Route.DIAGNOSTICS.path) { ScreenScaffold(Route.DIAGNOSTICS, nav) { snack -> DiagnosticsScreen(graph, snack) } }
        composable(Route.ABOUT.path) { ScreenScaffold(Route.ABOUT, nav) { _ -> AboutScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(route: Route, nav: NavHostController, root: Boolean = false, content: @Composable (SnackbarHostState) -> Unit) {
    val snack = remember { SnackbarHostState() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(route.title) },
                navigationIcon = {
                    if (!root) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            content(snack)
        }
    }
}

/** Conteúdo padrão de uma tela baseada em [ScreenViewModel]: loading/erro/pronto + snackbars. */
@Composable
fun <T> ViewModelScreen(vm: ScreenViewModel<T>, snack: SnackbarHostState, content: @Composable (T) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.messages.collect { snack.showSnackbar(it) } }
    when (val s = state) {
        UiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        is UiState.Failed -> Card(modifier = Modifier.padding(8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Falha ao carregar", style = MaterialTheme.typography.titleSmall)
                Text(s.message, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = null); Text(" Tentar novamente") }
            }
        }
        is UiState.Ready -> content(s.data)
    }
}

/** Lista rolável simples para corpos de tela. */
@Composable
fun ScrollBody(blocks: List<@Composable () -> Unit>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(blocks) { block -> block() }
    }
}

@Composable
fun ScrollBody(vararg blocks: @Composable () -> Unit) {
    ScrollBody(blocks.toList())
}
