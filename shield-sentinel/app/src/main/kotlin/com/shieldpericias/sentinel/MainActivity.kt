package com.shieldpericias.sentinel

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.shieldpericias.sentinel.ui.SentinelRoot
import com.shieldpericias.sentinel.ui.theme.SentinelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Proteção contra captura de tela/gravação dentro do Sentinel.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val graph = SentinelApp.graph(this)
        setContent {
            SentinelTheme {
                SentinelRoot(graph)
            }
        }
    }
}
