package com.example.radialkeyboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.radialkeyboard.audio.AudioManager
import com.example.radialkeyboard.viewmodel.KeyboardEvent
import com.example.radialkeyboard.viewmodel.KeyboardViewModel
import com.example.radialkeyboard.viewmodel.Ring

private val ColorBrandTeal   = Color(0xFF1B5E20)
private val ColorPlayButton  = Color(0xFF2E7D32)
private val ColorStopButton  = Color(0xFFB71C1C)
private val ColorTextBarBg   = Color.White
private val ColorTextContent = Color(0xFF1A1A1A)

@Composable
fun RadialKeyboardScreen() {
    val viewModel: KeyboardViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // ── Audio ────────────────────────────────────────────────────────────────
    val audio = remember { AudioManager(context) }
    DisposableEffect(audio) { onDispose { audio.shutdown() } }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, audio) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) audio.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    var isReading  by remember { mutableStateOf(false) }
    var cursorPos  by remember { mutableIntStateOf(-1) }

    // Instructions on first load
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

    // Suggestion readout when user navigates to a chip
    LaunchedEffect(uiState.selectedSuggestionIdx) {
        val idx = uiState.selectedSuggestionIdx
        if (idx >= 0 && idx < uiState.suggestions.size) {
            audio.speakText(uiState.suggestions[idx])
        }
    }

    // Post-commit audio: read current word prefix + suggestion list (300 ms delay like web)
    LaunchedEffect(uiState.typedText, uiState.suggestions) {
        if (isReading || uiState.typedText.isBlank()) return@LaunchedEffect
        val trimmed   = uiState.typedText.trimEnd()
        val lastSpace = trimmed.lastIndexOf(' ')
        val prefix    = if (lastSpace >= 0) trimmed.substring(lastSpace + 1) else trimmed
        val parts     = mutableListOf<String>()
        if (prefix.isNotBlank()) parts.add(prefix)
        if (uiState.suggestions.isNotEmpty())
            parts.add("നിർദ്ദേശങ്ങൾ: " + uiState.suggestions.joinToString(", "))
        if (parts.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        if (!isReading) audio.speakText(parts.joinToString(". "))
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
                cursorPos = cursorPos,
                onPlayStop = {
                    if (isReading) {
                        audio.stop()
                        isReading = false
                        cursorPos = -1
                    } else if (uiState.typedText.isNotBlank()) {
                        audio.stop()
                        isReading = true
                        cursorPos = 0
                        audio.speakForPlayback(
                            text     = uiState.typedText,
                            onCursor = { cursorPos = it },
                            onDone   = { isReading = false; cursorPos = -1 },
                        )
                    }
                },
                onCopy = {
                    if (uiState.typedText.isNotBlank())
                        clipboardManager.setText(AnnotatedString(uiState.typedText))
                },
            )
            SuggestionsBar(
                suggestions = uiState.suggestions,
                selectedIdx = uiState.selectedSuggestionIdx,
                onNavigate  = { dir -> viewModel.onEvent(KeyboardEvent.SuggestionNavigated(dir)) },
                onAccept    = { word ->
                    audio.stop(); isReading = false; cursorPos = -1
                    viewModel.onEvent(KeyboardEvent.SuggestionAccepted(word))
                },
            )
        }

        RadialKeyboardOverlay(
            uiState           = uiState,
            hapticEvents      = viewModel.hapticEvents,
            wheelRadiusDp     = 160.dp,
            onStringCommitted = { chars ->
                audio.stop(); isReading = false; cursorPos = -1
                viewModel.onEvent(KeyboardEvent.StringCommitted(chars))
            },
            onDelete          = { audio.stop(); isReading = false; cursorPos = -1; viewModel.onEvent(KeyboardEvent.DeleteLast) },
            onSpace           = { audio.stop(); isReading = false; cursorPos = -1; viewModel.onEvent(KeyboardEvent.SpacePressed) },
            onSubMenuFired    = { ring, idx ->
                audio.stop()
                viewModel.onEvent(KeyboardEvent.SubMenuFired(ring, idx))
            },
            onResetRings      = { viewModel.onEvent(KeyboardEvent.ResetRings) },
            onShow            = { offset ->
                audio.stop(); isReading = false; cursorPos = -1
                audio.playActivationSound()
                viewModel.onEvent(KeyboardEvent.KeyboardShown(offset))
            },
            onHide            = { viewModel.onEvent(KeyboardEvent.KeyboardHidden) },
            onBeyondDir       = { dir -> viewModel.onEvent(KeyboardEvent.BeyondDirChanged(dir)) },
            onNavigateSuggestion = { dir -> viewModel.onEvent(KeyboardEvent.SuggestionNavigated(dir)) },
            onAcceptHighlightedSuggestion = {
                val idx  = uiState.selectedSuggestionIdx
                val word = uiState.suggestions.getOrNull(idx)
                if (word != null) {
                    audio.stop(); isReading = false; cursorPos = -1
                    viewModel.onEvent(KeyboardEvent.SuggestionAccepted(word))
                }
            },
            onHighlight = { ring, idx ->
                if (ring != null && idx >= 0) {
                    audio.playClickSound(ring == Ring.INNER)
                    viewModel.onEvent(KeyboardEvent.SegmentHighlighted(ring, idx))
                } else {
                    viewModel.onEvent(KeyboardEvent.SegmentCleared)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Ring legend at bottom
        RingLegend(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        )
    }
}

// ─── Top text bar ─────────────────────────────────────────────────────────────

@Composable
fun TopTextBar(
    text: String,
    isReading: Boolean,
    cursorPos: Int = -1,
    onPlayStop: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Blinking cursor animation (only when not reading)
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 0f,
        animationSpec = infiniteRepeatable(
            animation   = keyframes {
                durationMillis = 900
                1f at 0   with LinearEasing
                1f at 450 with LinearEasing
                0f at 451 with LinearEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "cursorAlpha",
    )

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
                modifier = Modifier.background(
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

            // Text with cursor
            val displayText = buildAnnotatedString {
                when {
                    text.isEmpty() -> {
                        withStyle(SpanStyle(color = ColorTextContent.copy(alpha = 0.4f))) {
                            append("Start typing…")
                        }
                    }
                    isReading && cursorPos >= 0 && cursorPos < text.length -> {
                        // During readback: text before cursor normal, after cursor dimmed
                        append(text.substring(0, cursorPos))
                        withStyle(SpanStyle(color = ColorTextContent.copy(alpha = 0.30f))) {
                            append(text.substring(cursorPos))
                        }
                    }
                    else -> append(text)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text     = displayText,
                        style    = MaterialTheme.typography.bodyLarge,
                        color    = ColorTextContent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Blinking cursor shown when not reading
                    if (text.isNotEmpty() && !isReading) {
                        Text(
                            text     = "|",
                            style    = MaterialTheme.typography.bodyLarge,
                            color    = ColorTextContent,
                            modifier = Modifier.alpha(cursorAlpha),
                        )
                    }
                    // Green cursor dot shown during readback
                    if (isReading) {
                        Box(
                            modifier = Modifier
                                .padding(start = 3.dp)
                                .size(8.dp)
                                .background(Color(0xFF2E7D32), CircleShape),
                        )
                    }
                }
            }

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

// ─── Suggestions bar ─────────────────────────────────────────────────────────

private val ColorChipBg       = Color(0xFF1A3A1A)
private val ColorChipSelected = Color(0xFFFFD54F)
private val ColorChipText     = Color.White
private val ColorChipSelText  = Color(0xFF1A1A1A)
private val ColorChipPlaceholder = Color.White.copy(alpha = 0.25f)

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous", tint = ColorChipText)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (suggestions.isEmpty()) {
                    Text(
                        text     = "നിർദ്ദേശങ്ങൾ കാണുന്നതിനായി ടൈപ്പ് ചെയ്തു തുടങ്ങുക..",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = ColorChipPlaceholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(scrollState),
                    ) {
                        suggestions.forEachIndexed { i, word ->
                            val selected = i == selectedIdx
                            Surface(
                                shape    = RoundedCornerShape(6.dp),
                                color    = if (selected) ColorChipSelected else Color.White.copy(alpha = 0.12f),
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
                }
            }
            IconButton(onClick = { onNavigate(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next", tint = ColorChipText)
            }
        }
    }
}

// ─── Ring legend ─────────────────────────────────────────────────────────────

private val ColorLegendInner = Color(0xFF4A4540)
private val ColorLegendOuter = Color(0xFF3A3735)
private val ColorLegendSub   = Color(0xFFF9A825)

@Composable
fun RingLegend(modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment     = Alignment.CenterVertically,
        modifier              = modifier,
    ) {
        LegendItem(color = ColorLegendInner, label = "Inner — Consonants")
        LegendItem(color = ColorLegendOuter, label = "Outer — Vowels")
        LegendItem(color = ColorLegendSub,   label = "Sub-menu active")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(modifier = Modifier.size(9.dp).background(color, CircleShape))
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f),
        )
    }
}
