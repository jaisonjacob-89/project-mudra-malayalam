package com.example.radialkeyboard.ui

import com.example.radialkeyboard.audio.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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

private val ColorBrandTeal   = Color(0xFF1B5E20)
private val ColorSendButton  = Color(0xFF2E7D32)
private val ColorTextBarBg   = Color.White
private val ColorTextContent = Color(0xFF1A1A1A)

@Composable
fun RadialKeyboardScreen() {
    val viewModel: KeyboardViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ── Audio setup ──────────────────────────────────────────────────────────
    val audio = remember { AudioManager(context) }
    DisposableEffect(audio) { onDispose { audio.shutdown() } }

    // Pre-recorded clip on segment hover — stop any current clip first
    LaunchedEffect(uiState.highlightedRing, uiState.highlightedIdx) {
        val ring  = uiState.highlightedRing ?: run { audio.stop(); return@LaunchedEffect }
        val idx   = uiState.highlightedIdx.takeIf { it >= 0 } ?: run { audio.stop(); return@LaunchedEffect }
        val segs  = if (ring == Ring.INNER) uiState.innerSegs else uiState.outerSegs
        val label = segs.getOrNull(idx)?.label?.takeIf { it.isNotBlank() && it != "–" } ?: return@LaunchedEffect
        audio.playLetterSound(label)
    }

    // Speak selected suggestion via cached TTS
    LaunchedEffect(uiState.selectedSuggestionIdx) {
        val idx         = uiState.selectedSuggestionIdx
        val suggestions = uiState.suggestions
        if (idx >= 0 && idx < suggestions.size) {
            audio.speakText(suggestions[idx])
        }
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
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TopTextBar(
                text   = uiState.typedText,
                onSend = { viewModel.onEvent(KeyboardEvent.SendPressed) },
            )
            if (uiState.suggestions.isNotEmpty()) {
                SuggestionsBar(
                    suggestions   = uiState.suggestions,
                    selectedIdx   = uiState.selectedSuggestionIdx,
                    onNavigate    = { dir -> viewModel.onEvent(KeyboardEvent.SuggestionNavigated(dir)) },
                    onAccept      = { word -> viewModel.onEvent(KeyboardEvent.SuggestionAccepted(word)) },
                )
            }
        }

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
            onBeyondDir       = { dir -> viewModel.onEvent(KeyboardEvent.BeyondDirChanged(dir)) },
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
        modifier        = modifier.fillMaxWidth().heightIn(min = 52.dp),
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

private val ColorChipBg       = Color(0xFF1A3A1A)
private val ColorChipSelected = Color(0xFFFFD54F)
private val ColorChipText     = Color.White
private val ColorChipSelText  = Color(0xFF1A1A1A)

@Composable
fun SuggestionsBar(
    suggestions: List<String>,
    selectedIdx: Int,
    onNavigate: (Int) -> Unit,
    onAccept: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Surface(
        shape           = RoundedCornerShape(10.dp),
        color           = ColorChipBg,
        shadowElevation = 2.dp,
        modifier        = modifier.fillMaxWidth().heightIn(min = 44.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(onClick = { onNavigate(-1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,  contentDescription = "Previous", tint = ColorChipText)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
                    .padding(vertical = 6.dp),
            ) {
                suggestions.forEachIndexed { i, word ->
                    val selected = i == selectedIdx
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) ColorChipSelected else Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.clickable { onAccept(word) },
                    ) {
                        Text(
                            text     = word,
                            color    = if (selected) ColorChipSelText else ColorChipText,
                            style    = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            IconButton(onClick = { onNavigate(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", tint = ColorChipText)
            }
        }
    }
}
