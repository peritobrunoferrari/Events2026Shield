package com.shieldpericias.sentinel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD1E8),
    onPrimary = Color(0xFF00363F),
    secondary = Color(0xFFB2CBD3),
    background = Color(0xFF0E1A20),
    surface = Color(0xFF14232B),
    error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00677D),
    onPrimary = Color.White,
    secondary = Color(0xFF4A626A),
    background = Color(0xFFF6FAFC),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFBA1A1A),
)

@Composable
fun SentinelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

object StatusColors {
    val active = Color(0xFF2E9E5B)
    val inactive = Color(0xFFD08A00)
    val unavailable = Color(0xFF7A8A92)
    val error = Color(0xFFC63B3B)
}
