package com.example.radialkeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.radialkeyboard.ui.RadialKeyboardScreen
import com.example.radialkeyboard.ui.theme.MudraMalayalamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MudraMalayalamTheme {
                RadialKeyboardScreen()
            }
        }
    }
}
