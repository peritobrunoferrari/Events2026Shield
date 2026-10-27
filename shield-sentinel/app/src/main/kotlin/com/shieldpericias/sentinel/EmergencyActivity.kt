package com.shieldpericias.sentinel

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shieldpericias.sentinel.emergency.EmergencyIntents
import com.shieldpericias.sentinel.emergency.TriggerSource
import com.shieldpericias.sentinel.ui.screens.EmergencyConfirmScreen
import com.shieldpericias.sentinel.ui.theme.SentinelTheme

/** Tela de confirmação acionada por atalho ou Quick Settings Tile. */
class EmergencyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val graph = SentinelApp.graph(this)
        val source = runCatching { TriggerSource.valueOf(intent.getStringExtra(EmergencyIntents.EXTRA_SOURCE) ?: "") }.getOrDefault(TriggerSource.SHORTCUT)
        setContent {
            SentinelTheme {
                EmergencyConfirmScreen(graph = graph, source = source, onClose = { finish() })
            }
        }
    }
}
