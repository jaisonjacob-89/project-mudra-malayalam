package com.example.radialkeyboard.ui

import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.radialkeyboard.viewmodel.KeyboardEvent
import com.example.radialkeyboard.viewmodel.KeyboardViewModel
import com.example.radialkeyboard.viewmodel.Ring
import java.util.Locale

private val ColorBrandTeal   = Color(0xFF1B5E20)
private val ColorSendButton  = Color(0xFF2E7D32)
private val ColorTextBarBg   = Color.White
private val ColorTextContent = Color(0xFF1A1A1A)

@Composable
fun RadialKeyboardScreen() {
    val viewModel: KeyboardViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ── Text-to-Speech setup ─────────────────────────────────────────────────
    val ttsReady = remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context) { status ->
            ttsReady.value = status == TextToSpeech.SUCCESS
        }
    }
    LaunchedEffect(ttsReady.value) {
        if (ttsReady.value) {
            tts.language = Locale("ml", "IN")
            tts.setSpeechRate(1.1f)
        }
    }
    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    // Speak the label whenever the highlighted segment changes
    LaunchedEffect(uiState.highlightedRing, uiState.highlightedIdx) {
        if (!ttsReady.value) return@LaunchedEffect
        val ring = uiState.highlightedRing ?: return@LaunchedEffect
        val idx  = uiState.highlightedIdx.takeIf { it >= 0 } ?: return@LaunchedEffect
        val segs = if (ring == Ring.INNER) uiState.innerSegs else uiState.outerSegs
        val label = segs.getOrNull(idx)?.label?.takeIf { it.isNotBlank() && it != "–" } ?: return@LaunchedEffect
        tts.speak(label, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    LaunchedEffect(viewModel) {
        viewModel.sentMessages.collect { /* delivered to chat */ }
    }

    BackHandler(enabled = uiState.innerSub || uiState.outerSub) {
        viewModel.onEvent(KeyboardEvent.ResetRings)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBrandTeal),
    ) {
        TopTextBar(
            text     = uiState.typedText,
            onSend   = { viewModel.onEvent(KeyboardEvent.SendPressed) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )

        RadialKeyboardOverlay(
            uiState           = uiState,
            hapticEvents      = viewModel.hapticEvents,
            wheelRadiusDp     = 160.dp,
            onStringCommitted = { chars -> viewModel.onEvent(KeyboardEvent.StringCommitted(chars)) },
            onDelete          = { viewModel.onEvent(KeyboardEvent.DeleteLast) },
            onSpace           = { viewModel.onEvent(KeyboardEvent.SpacePressed) },
            onSubMenuFired    = { ring, idx -> viewModel.onEvent(KeyboardEvent.SubMenuFired(ring, idx)) },
            onResetRings      = { viewModel.onEvent(KeyboardEvent.ResetRings) },
            onShow            = { offset -> viewModel.onEvent(KeyboardEvent.KeyboardShown(offset)) },
            onHide            = { viewModel.onEvent(KeyboardEvent.KeyboardHidden) },
            onHighlight       = { ring, idx ->
                if (ring != null && idx >= 0)
                    viewModel.onEvent(KeyboardEvent.SegmentHighlighted(ring, idx))
                else
                    viewModel.onEvent(KeyboardEvent.SegmentCleared)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun TopTextBar(
    text: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape           = RoundedCornerShape(12.dp),
        color           = ColorTextBarBg,
        shadowElevation = 4.dp,
        modifier        = modifier.heightIn(min = 52.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
        ) {
            Text(
                text     = text.ifEmpty { "Start typing…" },
                style    = MaterialTheme.typography.bodyLarge,
                color    = if (text.isEmpty()) ColorTextContent.copy(alpha = 0.4f)
                           else ColorTextContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick  = onSend,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .background(color = ColorSendButton, shape = RoundedCornerShape(8.dp)),
            ) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint               = Color.White,
                )
            }
        }
    }
}
