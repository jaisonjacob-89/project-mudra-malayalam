package com.example.radialkeyboard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AppColorScheme = darkColorScheme(
    primary    = Color(0xFF4CAF50),
    secondary  = Color(0xFF00897B),
    background = Color(0xFF00897B),
    surface    = Color(0xFF00897B),
)

@Composable
fun MudraMalayalamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        content     = content,
    )
}
