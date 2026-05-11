package com.example.radialkeyboard.ui

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.radialkeyboard.audio.AudioManager
import com.example.radialkeyboard.viewmodel.KeyboardEvent
import com.example.radialkeyboard.viewmodel.KeyboardViewModel
import com.example.radialkeyboard.viewmodel.Ring

private val ColorBrandTeal    = Color(0xFF1B5E20)
private val ColorPlayButton   = Color(0xFF2E7D32)
private val ColorStopButton   = Color(0xFFB71C1C)
private val ColorTextBarBg    = Color.White
private val ColorTextContent  = Color(0xFF1A1A1A)

@Composable
fun RadialKeyboardScreen() {
    val viewModel: KeyboardViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // ── Audio ────────────────────────────────────────────────────────────────
    val audio = remember { AudioManager(context) }
    DisposableEffect(audio) { onDispose { audio.shutdown() } }

    // Stop audio when app goes to background
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, audio) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) audio.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Track whether composed text is being read aloud
    var isReading by remember { mutableStateOf(false) }

    // Instructions on first load (800 ms delay matches web version)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(800)
        audio.playInstructions()
    }

    // Letter sound on segment hover
    LaunchedEffect(uiState.highlightedRing, uiState.highlightedIdx) {
        val ring  = uiState.highlightedRing ?: run { audio.stop(); return@LaunchedEffect }
        val idx   = uiState.highlightedIdx.takeIf { it >= 0 } ?: run { audio.stop(); return@LaunchedEffect }
        val segs  = if (ring == Ring.INNER) uiState.innerSegs else uiState.outerSegs
        val label = segs.getOrNull(idx)?.label?.takeIf { it.isNotBlank() && it != "–" } ?: return@LaunchedEffect
        audio.playLetterSound(label)
    }

    // Suggestion readout on navigation
    LaunchedEffect(uiState.selectedSuggestionIdx) {
        val idx         = uiState.selectedSuggestionIdx
        val suggestions = uiState.suggestions
        if (idx >= 0 && idx < suggestions.size) {
            audio.speakText(suggestions[idx])
        }
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
                text      = uiState.typedText,
                isReading = isReading,
                onPlayStop = {
                    if (isReading) {
                        audio.stop()
                        isReading = false
                    } else if (uiState.typedText.isNotBlank()) {
                        audio.stop()
                        isReading = true
                        audio.speakText(uiState.typedText) { isReading = false }
                    }
                },
                onCopy = {
                    if (uiState.typedText.isNotBlank()) {
                        clipboardManager.setText(AnnotatedString(uiState.typedText))
                    }
                },
            )
            if (uiState.suggestions.isNotEmpty()) {
                SuggestionsBar(
                    suggestions = uiState.suggestions,
                    selectedIdx = uiState.selectedSuggestionIdx,
                    onNavigate  = { dir -> viewModel.onEvent(KeyboardEvent.SuggestionNavigated(dir)) },
                    onAccept    = { word ->
                        audio.stop()
                        isReading = false
                        viewModel.onEvent(KeyboardEvent.SuggestionAccepted(word))
                    },
                )
            }
        }

        RadialKeyboardOverlay(
            uiState           = uiState,
            hapticEvents      = viewModel.hapticEvents,
            wheelRadiusDp     = 160.dp,
            onStringCommitted = { chars ->
                audio.stop(); isReading = false
                viewModel.onEvent(KeyboardEvent.StringCommitted(chars))
            },
            onDelete          = { audio.stop(); isReading = false; viewModel.onEvent(KeyboardEvent.DeleteLast) },
            onSpace           = { audio.stop(); isReading = false; viewModel.onEvent(KeyboardEvent.SpacePressed) },
            onSubMenuFired    = { ring, idx ->
                audio.stop()
                viewModel.onEvent(KeyboardEvent.SubMenuFired(ring, idx))
            },
            onResetRings      = { viewModel.onEvent(KeyboardEvent.ResetRings) },
            onShow            = { offset ->
                audio.stop(); isReading = false
                viewModel.onEvent(KeyboardEvent.KeyboardShown(offset))
            },
            onHide            = { viewModel.onEvent(KeyboardEvent.KeyboardHidden) },
            onBeyondDir       = { dir -> viewModel.onEvent(KeyboardEvent.BeyondDirChanged(dir)) },
            onNavigateSuggestion = { dir -> viewModel.onEvent(KeyboardEvent.SuggestionNavigated(dir)) },
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
    isReading: Boolean,
    onPlayStop: () -> Unit,
    onCopy: () -> Unit,
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
                .padding(horizontal = 4.dp),
        ) {
            IconButton(
                onClick  = onPlayStop,
                modifier = Modifier
                    .background(
                        color = if (isReading) ColorStopButton else ColorPlayButton,
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                Icon(
                    imageVector        = if (isReading) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isReading) "Stop" else "Play",
                    tint               = Color.White,
                )
            }
            Text(
                text     = text.ifEmpty { "Start typing…" },
                style    = MaterialTheme.typography.bodyLarge,
                color    = if (text.isEmpty()) ColorTextContent.copy(alpha = 0.4f)
                           else ColorTextContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector        = Icons.Filled.ContentCopy,
                    contentDescription = "Copy text",
                    tint               = ColorTextContent.copy(alpha = 0.6f),
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
