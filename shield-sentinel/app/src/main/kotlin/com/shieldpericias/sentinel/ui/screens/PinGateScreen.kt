package com.shieldpericias.sentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shieldpericias.sentinel.SentinelGraph
import com.shieldpericias.sentinel.core.duress.PinKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Portão de PIN da interface. PIN normal → administração. PIN duress → executa
 * a política configurada e mantém a tela neutra (sem disfarce, sem feedback
 * diferenciado). O trabalho criptográfico é constante para ambos os PINs.
 */
@Composable
fun PinGateScreen(graph: SentinelGraph, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Shield Sentinel", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pin, onValueChange = { pin = it }, label = { Text("PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(), enabled = !busy,
        )
        Spacer(Modifier.height(12.dp))
        Button(enabled = pin.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            val entered = pin.toCharArray(); pin = ""; busy = true; message = null
            scope.launch {
                val backoff = withContext(Dispatchers.IO) { graph.duressPins.backoffMs() }
                if (backoff > 0) delay(backoff)
                val kind = withContext(Dispatchers.IO) { graph.duressPins.classify(entered) }
                entered.fill('\u0000')
                when (kind) {
                    PinKind.NORMAL -> onUnlocked()
                    PinKind.DURESS -> {
                        withContext(Dispatchers.IO) { graph.duressExecutor.execute(graph.duressPins.policy()) }
                        message = "PIN incorreto"
                    }
                    PinKind.INVALID -> message = "PIN incorreto"
                }
                busy = false
            }
        }) { Text("Entrar") }
        message?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
