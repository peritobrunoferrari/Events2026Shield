package com.shieldpericias.sentinel

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shieldpericias.sentinel.ui.Route
import com.shieldpericias.sentinel.ui.SentinelRoot
import com.shieldpericias.sentinel.ui.theme.SentinelTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Extra (somente builds debug) para abrir uma tela específica: `--es sentinel.route usb`. */
        const val EXTRA_ROUTE = "sentinel.route"
        /** Extra (somente builds debug) que desliga FLAG_SECURE para capturas em emulador de teste. */
        const val EXTRA_ALLOW_CAPTURE = "sentinel.debug.allowCapture"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val allowCapture = debuggable && intent.getBooleanExtra(EXTRA_ALLOW_CAPTURE, false)
        // Proteção contra captura de tela/gravação dentro do Sentinel (sempre ativa em release).
        if (!allowCapture) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val startRoute = if (debuggable) intent.getStringExtra(EXTRA_ROUTE)?.let { r -> Route.entries.firstOrNull { it.path == r } } else null
        val graph = SentinelApp.graph(this)
        setContent {
            SentinelTheme {
                SentinelRoot(graph, startRoute ?: Route.DASHBOARD)
            }
        }
    }
}
