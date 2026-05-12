package com.example.radialkeyboard.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.radialkeyboard.viewmodel.HapticType
import com.example.radialkeyboard.viewmodel.KeyboardUiState
import com.example.radialkeyboard.viewmodel.Ring
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ─── Visual constants ────────────────────────────────────────────────────────

private val C_OUTER_BASE   = Color(0xFF3A3735)
private val C_INNER_BASE   = Color(0xFF2D2B29)
private val C_OUTER_HI     = Color(0xFF90CAF9)
private val C_INNER_HI     = Color(0xFF80DEEA)
private val C_SUB_BASE     = Color(0xFF4A3B00)
private val C_SUB_HI       = Color(0xFFFFD54F)
private val C_LP_PROGRESS  = Color(0xFFFFD54F)
private val C_CENTER       = Color.White

private const val N           = 10
private const val SWEEP_DEG   = 36f
private const val F_SPLIT     = 0.60f
private const val F_DEAD      = 0.20f
private const val LP_MS       = 1500L
private const val WHEEL_ALPHA = 0.93f
private const val DOUBLE_TAP_MS = 350L
private const val WHEEL_DELAY_MS = 80L

// ─── Composable ──────────────────────────────────────────────────────────────

@Composable
fun RadialKeyboardOverlay(
    uiState: KeyboardUiState,
    hapticEvents: Flow<HapticType>,
    wheelRadiusDp: Dp = 160.dp,
    onStringCommitted: (String) -> Unit,
    onDelete: () -> Unit,
    onSpace: () -> Unit,
    onSubMenuFired: (Ring, Int) -> Unit,
    onResetRings: () -> Unit,
    onShow: (Offset) -> Unit,
    onHide: () -> Unit,
    onHighlight: (Ring?, Int) -> Unit,
    onBeyondDir: (Int) -> Unit = {},
    onNavigateSuggestion: (Int) -> Unit = {},
    onAcceptHighlightedSuggestion: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope          = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(hapticEvents) {
        hapticEvents.collect { type ->
            when (type) {
                HapticType.SEGMENT_ENTRY    -> hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                HapticType.KEYBOARD_APPEAR  -> hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                HapticType.CHARACTER_COMMIT -> hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    // Scale-in / scale-out animation
    val scaleAnim = remember { Animatable(0f) }
    LaunchedEffect(uiState.isKeyboardVisible) {
        if (uiState.isKeyboardVisible)
            scaleAnim.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
        else
            scaleAnim.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
    }

    // Per-ring alpha — fades in independently as finger enters each zone
    val innerAlpha = remember { Animatable(0f) }
    val outerAlpha = remember { Animatable(0f) }

    // Long-press state
    var lpProgress by remember { mutableFloatStateOf(0f) }
    var lpRing     by remember { mutableStateOf<Ring?>(null) }
    var lpIdx      by remember { mutableIntStateOf(-1) }
    var lpJob      by remember { mutableStateOf<Job?>(null) }

    // Ring rotation offsets
    var innerRot by remember { mutableFloatStateOf(-90f) }
    var outerRot by remember { mutableFloatStateOf(-90f) }

    // Double-tap tracking — persists across gestures
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    val currentUiState by rememberUpdatedState(uiState)

    fun fireSubMenu(ring: Ring, idx: Int) {
        when (ring) {
            Ring.INNER -> innerRot = ((innerRot + idx * SWEEP_DEG) % 360f + 360f) % 360f
            Ring.OUTER -> outerRot = ((outerRot + idx * SWEEP_DEG) % 360f + 360f) % 360f
        }
        onHighlight(null, -1)
        onSubMenuFired(ring, idx)
    }

    fun startLongPress(ring: Ring, idx: Int) {
        lpJob?.cancel()
        lpRing = ring; lpIdx = idx; lpProgress = 0f
        lpJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (true) {
                delay(16L)
                lpProgress = ((System.currentTimeMillis() - startMs) / LP_MS.toFloat()).coerceIn(0f, 1f)
                if (lpProgress >= 1f) {
                    fireSubMenu(ring, idx)
                    lpRing = null; lpIdx = -1; lpProgress = 0f
                    break
                }
            }
        }
    }

    fun cancelLongPress() {
        lpJob?.cancel(); lpJob = null
        lpRing = null; lpIdx = -1; lpProgress = 0f
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down    = awaitFirstDown(requireUnconsumed = false)
                    val wheelPx = wheelRadiusDp.toPx()
                    val raw     = down.position
                    val touchOrigin = Offset(
                        raw.x.coerceIn(wheelPx, size.width.toFloat()  - wheelPx),
                        raw.y.coerceIn(wheelPx, size.height.toFloat() - wheelPx),
                    )
                    innerRot = -90f; outerRot = -90f
                    var innerRotSet = false; var outerRotSet = false
                    var lastRing: Ring? = null
                    var lastIdx        = -1
                    var lastBeyond     = 0

                    // Two-finger swipe state
                    var twoFingerMode   = false
                    var twoFingerStartX = 0f
                    var twoFingerStartY = 0f
                    var twoFingerSwiped = false
                    val swipeThreshPx  = 35.dp.toPx()

                    // Per-ring fade-in flags (prevent re-launching animation)
                    var innerFaded = false
                    var outerFaded = false

                    // 80 ms delayed wheel show
                    var wheelShown = false
                    val showJob = scope.launch {
                        delay(WHEEL_DELAY_MS)
                        scope.launch { innerAlpha.snapTo(0f); outerAlpha.snapTo(0f) }
                        onShow(touchOrigin)
                        wheelShown = true
                    }

                    try {
                        var event = awaitPointerEvent()
                        while (true) {
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty() || event.type == PointerEventType.Exit) break

                            // ── Second finger → two-finger mode ──────────────
                            if (pressed.size >= 2 && !twoFingerMode) {
                                twoFingerMode = true
                                showJob.cancel()
                                cancelLongPress()
                                onHighlight(null, -1)
                                lastRing = null; lastIdx = -1
                                twoFingerStartX = pressed.map { it.position.x }.average().toFloat()
                                twoFingerStartY = pressed.map { it.position.y }.average().toFloat()
                            }

                            if (twoFingerMode) {
                                if (!twoFingerSwiped && pressed.size >= 2) {
                                    val avgX = pressed.map { it.position.x }.average().toFloat()
                                    val avgY = pressed.map { it.position.y }.average().toFloat()
                                    val dx   = avgX - twoFingerStartX
                                    val dy   = avgY - twoFingerStartY
                                    if (abs(dx) >= swipeThreshPx && abs(dx) >= 2f * abs(dy)) {
                                        twoFingerSwiped = true
                                        onNavigateSuggestion(if (dx > 0) 1 else -1)
                                    }
                                }
                            } else {
                                val pos  = pressed.firstOrNull()?.position ?: break

                                // Early wheel show on clear vertical movement
                                if (!wheelShown && showJob.isActive) {
                                    if (abs(pos.y - raw.y) > 20.dp.toPx()) {
                                        showJob.cancel()
                                        scope.launch { innerAlpha.snapTo(0f); outerAlpha.snapTo(0f) }
                                        onShow(touchOrigin)
                                        wheelShown = true
                                    } else {
                                        event.changes.forEach { it.consume() }
                                        event = awaitPointerEvent()
                                        continue
                                    }
                                }

                                if (!wheelShown) {
                                    event.changes.forEach { it.consume() }
                                    event = awaitPointerEvent()
                                    continue
                                }

                                val dx   = pos.x - touchOrigin.x
                                val dy   = pos.y - touchOrigin.y
                                val dist = sqrt(dx * dx + dy * dy)
                                val dead  = wheelPx * F_DEAD
                                val split = wheelPx * F_SPLIT

                                var rawDeg = (atan2(dy, dx) * (180.0 / PI)).toFloat()
                                if (rawDeg < 0) rawDeg += 360f

                                if (dist >= dead && dist < split && !innerRotSet) {
                                    innerRot = rawDeg; innerRotSet = true
                                }
                                if (dist >= split && dist <= wheelPx && !outerRotSet) {
                                    outerRot = rawDeg; outerRotSet = true
                                }

                                // Per-ring fade-in as finger enters each zone
                                if (dist >= dead && dist < split) {
                                    if (!innerFaded) {
                                        innerFaded = true
                                        scope.launch { innerAlpha.animateTo(1f, tween(150)) }
                                    }
                                } else if (dist >= split && dist <= wheelPx) {
                                    if (!innerFaded) {
                                        innerFaded = true
                                        scope.launch { innerAlpha.animateTo(1f, tween(150)) }
                                    }
                                    if (!outerFaded) {
                                        outerFaded = true
                                        scope.launch { outerAlpha.animateTo(1f, tween(150)) }
                                    }
                                }

                                val newBeyond = if (dist > wheelPx) (if (dx > 0) 1 else -1) else 0
                                if (newBeyond != lastBeyond) {
                                    lastBeyond = newBeyond
                                    onBeyondDir(newBeyond)
                                    if (newBeyond != 0) cancelLongPress()
                                }

                                val (ring, idx) = hitTestDual(touchOrigin, pos, wheelPx, innerRot, outerRot)

                                if (newBeyond == 0 && (ring != lpRing || idx != lpIdx)) {
                                    cancelLongPress()
                                    if (ring != null && idx >= 0) startLongPress(ring, idx)
                                }

                                if (ring != lastRing || idx != lastIdx) {
                                    lastRing = ring; lastIdx = idx
                                    onHighlight(ring, idx)
                                }
                            }

                            event.changes.forEach { it.consume() }
                            event = awaitPointerEvent()
                        }

                        // ── LIFT ─────────────────────────────────────────────
                        showJob.cancel()
                        cancelLongPress()

                        if (wheelShown && !twoFingerMode) {
                            val finalPos  = event.changes.firstOrNull()?.position ?: touchOrigin
                            val dxC       = finalPos.x - touchOrigin.x
                            val dyC       = finalPos.y - touchOrigin.y
                            val distFinal = sqrt(dxC * dxC + dyC * dyC)
                            val deadZone  = wheelPx * F_DEAD

                            // Double-tap → accept highlighted suggestion
                            val now = System.currentTimeMillis()
                            val isDoubleTap = (now - lastTapTimeMs) < DOUBLE_TAP_MS &&
                                currentUiState.selectedSuggestionIdx >= 0 &&
                                currentUiState.suggestions.isNotEmpty()
                            lastTapTimeMs = now

                            if (isDoubleTap) {
                                onAcceptHighlightedSuggestion()
                            } else {
                                when {
                                    distFinal > wheelPx -> {
                                        if (dxC > 0) onSpace() else onDelete()
                                    }
                                    distFinal < deadZone -> {
                                        if (currentUiState.innerSub || currentUiState.outerSub) {
                                            innerRot = -90f; outerRot = -90f
                                            onResetRings()
                                        }
                                    }
                                    lastRing != null && lastIdx >= 0 -> {
                                        val segs = if (lastRing == Ring.INNER)
                                            currentUiState.innerSegs
                                        else
                                            currentUiState.outerSegs
                                        segs.getOrNull(lastIdx)?.chars?.let { onStringCommitted(it) }
                                    }
                                }
                            }
                        }
                    } finally {
                        showJob.cancel()
                        cancelLongPress()
                        if (wheelShown) onHide()
                        scope.launch { innerAlpha.snapTo(0f); outerAlpha.snapTo(0f) }
                        onHighlight(null, -1)
                    }
                }
            }
    ) {
        val iAlpha = innerAlpha.value
        val oAlpha = outerAlpha.value

        // ── TRIGGER ZONE — shown when wheel is hidden ────────────────────────
        if (!uiState.isKeyboardVisible && scaleAnim.value < 0.05f) {
            val pad = wheelRadiusDp.toPx() + 8.dp.toPx()
            val dashPx = 10.dp.toPx()
            drawRoundRect(
                color       = Color.White.copy(alpha = 0.40f),
                topLeft     = Offset(pad, pad),
                size        = Size(size.width - pad * 2, size.height - pad * 2),
                cornerRadius = CornerRadius(14.dp.toPx()),
                style       = Stroke(
                    width      = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx * 0.8f))
                ),
            )
            // "tap here" label
            drawIntoCanvas { canvas ->
                val p = android.graphics.Paint().apply {
                    color       = android.graphics.Color.argb(100, 255, 255, 255)
                    textSize    = 13.dp.toPx()
                    isAntiAlias = true
                    textAlign   = android.graphics.Paint.Align.CENTER
                    typeface    = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
                }
                canvas.nativeCanvas.drawText(
                    "ടൈപ്പ് ചെയ്യാൻ ഇവിടെ സ്പർശിക്കുക",
                    size.width / 2f, size.height / 2f + p.textSize * 0.4f, p
                )
            }
            // Hint text at bottom
            drawIntoCanvas { canvas ->
                val p = android.graphics.Paint().apply {
                    color       = android.graphics.Color.argb(97, 255, 255, 255)
                    textSize    = 11.dp.toPx()
                    isAntiAlias = true
                    textAlign   = android.graphics.Paint.Align.CENTER
                }
                canvas.nativeCanvas.drawText(
                    "അക്ഷരം ചേർക്കാൻ വിരൽ ഉയർത്തുക  ·  സബ്‌മെനു: ഒരു സെക്കൻഡ് അമർത്തുക",
                    size.width / 2f, size.height - pad * 0.55f, p
                )
            }
            return@Canvas
        }

        if (scaleAnim.value == 0f) return@Canvas

        val scale  = scaleAnim.value
        val center = uiState.keyboardCenter
        val R      = wheelRadiusDp.toPx() * scale
        val Rsplit = R * F_SPLIT
        val Rdead  = R * F_DEAD

        // ── OUTER RING ───────────────────────────────────────────────────────
        for (i in 0 until N) {
            val startDeg = outerRot + i * SWEEP_DEG
            val isHi     = uiState.highlightedRing == Ring.OUTER && uiState.highlightedIdx == i
            val isSub    = uiState.outerSub
            val seg      = uiState.outerSegs.getOrNull(i)
            val baseAlpha = WHEEL_ALPHA * oAlpha
            val fill = when {
                seg == null -> Color(0xFF282320).copy(alpha = 0.6f * oAlpha)
                isHi        -> if (isSub) C_SUB_HI else C_OUTER_HI
                isSub       -> C_SUB_BASE
                else        -> C_OUTER_BASE
            }
            drawAnnulusSector(center, Rsplit, R, startDeg, SWEEP_DEG,
                fill.copy(alpha = if (seg == null) 0.6f * oAlpha else baseAlpha))
        }

        // ── INNER RING ───────────────────────────────────────────────────────
        for (i in 0 until N) {
            val startDeg = innerRot + i * SWEEP_DEG
            val isHi     = uiState.highlightedRing == Ring.INNER && uiState.highlightedIdx == i
            val isSub    = uiState.innerSub
            val seg      = uiState.innerSegs.getOrNull(i)
            val baseAlpha = WHEEL_ALPHA * iAlpha
            val fill = when {
                seg == null -> Color(0xFF1E1C1A).copy(alpha = 0.6f * iAlpha)
                isHi        -> if (isSub) C_SUB_HI else C_INNER_HI
                isSub       -> C_SUB_BASE
                else        -> C_INNER_BASE
            }
            drawAnnulusSector(center, Rdead, Rsplit, startDeg, SWEEP_DEG,
                fill.copy(alpha = if (seg == null) 0.6f * iAlpha else baseAlpha))
        }

        // ── LONG-PRESS PROGRESS ARC ──────────────────────────────────────────
        if (lpRing != null && lpIdx >= 0 && lpProgress > 0f) {
            val rot  = if (lpRing == Ring.INNER) innerRot else outerRot
            val sDeg = rot + lpIdx * SWEEP_DEG
            val r1   = if (lpRing == Ring.INNER) Rdead  else Rsplit
            val r2   = if (lpRing == Ring.INNER) Rsplit else R

            drawAnnulusSector(center, r1, r2, sDeg, SWEEP_DEG, C_LP_PROGRESS.copy(alpha = 0.20f))
            if (lpProgress > 0.01f) {
                drawAnnulusSector(center, r1, r2, sDeg, SWEEP_DEG * lpProgress, C_LP_PROGRESS.copy(alpha = 0.55f))
            }
            val midRad  = (sDeg + SWEEP_DEG / 2f) * (PI / 180.0).toFloat()
            val midR    = r1 + (r2 - r1) * 0.5f
            val lx      = center.x + cos(midRad) * midR
            val ly      = center.y + sin(midRad) * midR
            val secLeft = ceil(LP_MS * (1f - lpProgress) / 1000f).toInt().coerceAtLeast(1)
            drawIntoCanvas { canvas ->
                val p = android.graphics.Paint().apply {
                    color       = android.graphics.Color.argb(230, 255, 220, 80)
                    textSize    = R * 0.085f
                    isAntiAlias = true
                    textAlign   = android.graphics.Paint.Align.CENTER
                    typeface    = android.graphics.Typeface.DEFAULT_BOLD
                }
                canvas.nativeCanvas.drawText("${secLeft}s", lx, ly + p.textSize * 0.35f, p)
            }
        }

        // ── DIVIDER LINES ────────────────────────────────────────────────────
        for (i in 0 until N) {
            val rad = (innerRot + i * SWEEP_DEG) * (PI / 180.0).toFloat()
            drawLine(Color.White.copy(alpha = 0.14f * iAlpha),
                Offset(center.x + cos(rad) * Rdead,  center.y + sin(rad) * Rdead),
                Offset(center.x + cos(rad) * Rsplit, center.y + sin(rad) * Rsplit), 1.2f)
        }
        for (i in 0 until N) {
            val rad = (outerRot + i * SWEEP_DEG) * (PI / 180.0).toFloat()
            drawLine(Color.White.copy(alpha = 0.14f * oAlpha),
                Offset(center.x + cos(rad) * Rsplit, center.y + sin(rad) * Rsplit),
                Offset(center.x + cos(rad) * R,      center.y + sin(rad) * R), 1.2f)
        }

        // ── RING-SPLIT CIRCLE ────────────────────────────────────────────────
        val splitAlpha = maxOf(iAlpha, oAlpha)
        drawCircle(Color.White.copy(alpha = 0.20f * splitAlpha), radius = Rsplit, center = center, style = Stroke(1.3f))

        // ── OUTER EDGE ───────────────────────────────────────────────────────
        drawCircle(Color.White.copy(alpha = 0.09f * oAlpha), radius = R, center = center, style = Stroke(1.5f))

        // ── OUTER RING LABELS ────────────────────────────────────────────────
        val outerLabelR = Rsplit + (R - Rsplit) * 0.52f
        for (i in 0 until N) {
            val seg    = uiState.outerSegs.getOrNull(i)
            val midRad = (outerRot + i * SWEEP_DEG + SWEEP_DEG / 2f) * (PI / 180.0).toFloat()
            drawRingLabel(
                label         = seg?.label ?: "–",
                px            = center.x + cos(midRad) * outerLabelR,
                py            = center.y + sin(midRad) * outerLabelR,
                radiusPx      = R,
                isNull        = seg == null,
                isHighlighted = uiState.highlightedRing == Ring.OUTER && uiState.highlightedIdx == i,
                isSub         = uiState.outerSub,
                isInner       = false,
                alphaScale    = oAlpha,
            )
        }

        // ── INNER RING LABELS ────────────────────────────────────────────────
        val innerLabelR = Rdead + (Rsplit - Rdead) * 0.52f
        for (i in 0 until N) {
            val seg    = uiState.innerSegs.getOrNull(i)
            val midRad = (innerRot + i * SWEEP_DEG + SWEEP_DEG / 2f) * (PI / 180.0).toFloat()
            drawRingLabel(
                label         = seg?.label ?: "–",
                px            = center.x + cos(midRad) * innerLabelR,
                py            = center.y + sin(midRad) * innerLabelR,
                radiusPx      = R,
                isNull        = seg == null,
                isHighlighted = uiState.highlightedRing == Ring.INNER && uiState.highlightedIdx == i,
                isSub         = uiState.innerSub,
                isInner       = true,
                alphaScale    = iAlpha,
            )
        }

        // ── HIGHLIGHT OUTLINE GLOW ───────────────────────────────────────────
        if (uiState.highlightedRing != null && uiState.highlightedIdx >= 0) {
            val rot   = if (uiState.highlightedRing == Ring.INNER) innerRot else outerRot
            val sDeg  = rot + uiState.highlightedIdx * SWEEP_DEG
            val r1    = if (uiState.highlightedRing == Ring.INNER) Rdead  else Rsplit
            val r2    = if (uiState.highlightedRing == Ring.INNER) Rsplit else R
            val isSub = if (uiState.highlightedRing == Ring.INNER) uiState.innerSub else uiState.outerSub
            val glow  = if (isSub) C_SUB_HI
                        else if (uiState.highlightedRing == Ring.INNER) C_INNER_HI else C_OUTER_HI
            val path  = Path().apply { annulusSectorPath(center, r1 + 1f, r2 - 1f, sDeg, SWEEP_DEG) }
            drawPath(path, glow, style = Stroke(2.5f))
        }

        // ── CENTER CIRCLE ────────────────────────────────────────────────────
        drawCircle(C_CENTER, radius = Rdead, center = center)

        // ── SWIPE-BEYOND HINT (SPC / ⌫) ─────────────────────────────────────
        if (uiState.beyondDir != 0) {
            val hintLabel = if (uiState.beyondDir > 0) "SPC" else "⌫"
            val hintX     = center.x + uiState.beyondDir * R * 1.28f
            drawIntoCanvas { canvas ->
                val p = android.graphics.Paint().apply {
                    color       = android.graphics.Color.argb(200, 255, 255, 255)
                    textSize    = R * 0.14f
                    isAntiAlias = true
                    textAlign   = android.graphics.Paint.Align.CENTER
                    typeface    = android.graphics.Typeface.DEFAULT_BOLD
                }
                canvas.nativeCanvas.drawText(hintLabel, hintX, center.y + p.textSize * 0.35f, p)
            }
        }
    }
}

// ─── Geometry ────────────────────────────────────────────────────────────────

private data class HitResult(val ring: Ring?, val idx: Int)
private operator fun HitResult.component1() = ring
private operator fun HitResult.component2() = idx

private fun hitTestDual(
    origin: Offset, finger: Offset, wheelPx: Float,
    innerRot: Float = -90f, outerRot: Float = -90f,
): HitResult {
    val dx   = finger.x - origin.x
    val dy   = finger.y - origin.y
    val dist = sqrt(dx * dx + dy * dy)
    val dead = wheelPx * F_DEAD

    if (dist < dead || dist > wheelPx) return HitResult(null, -1)

    var deg = (atan2(dy, dx) * (180.0 / PI)).toFloat()
    if (deg < 0) deg += 360f

    val ring = if (dist < wheelPx * F_SPLIT) Ring.INNER else Ring.OUTER
    val rot  = if (ring == Ring.INNER) innerRot else outerRot
    val logical = ((deg - rot) % 360f + 360f) % 360f
    val idx     = (logical / SWEEP_DEG).toInt() % N

    return HitResult(ring, idx)
}

private fun Path.annulusSectorPath(
    center: Offset, innerR: Float, outerR: Float,
    startDeg: Float, sweepDeg: Float,
) {
    arcTo(
        rect              = Rect(center.x - outerR, center.y - outerR, center.x + outerR, center.y + outerR),
        startAngleDegrees = startDeg,
        sweepAngleDegrees = sweepDeg,
        forceMoveTo       = true,
    )
    arcTo(
        rect              = Rect(center.x - innerR, center.y - innerR, center.x + innerR, center.y + innerR),
        startAngleDegrees = startDeg + sweepDeg,
        sweepAngleDegrees = -sweepDeg,
        forceMoveTo       = false,
    )
    close()
}

private fun DrawScope.drawAnnulusSector(
    center: Offset, innerR: Float, outerR: Float,
    startDeg: Float, sweepDeg: Float, color: Color,
) {
    drawPath(Path().apply { annulusSectorPath(center, innerR, outerR, startDeg, sweepDeg) }, color)
}

// ─── Draw helpers ────────────────────────────────────────────────────────────

private fun DrawScope.drawRingLabel(
    label: String,
    px: Float, py: Float,
    radiusPx: Float,
    isNull: Boolean,
    isHighlighted: Boolean,
    isSub: Boolean,
    isInner: Boolean,
    alphaScale: Float = 1f,
) {
    if (alphaScale < 0.01f) return
    drawIntoCanvas { canvas ->
        val cpCount = label.codePointCount(0, label.length)
        val fsPx    = radiusPx * when {
            isNull   -> 0.07f
            isInner  -> if (cpCount <= 1) 0.115f else if (cpCount <= 2) 0.090f else 0.072f
            else     -> if (cpCount <= 1) 0.150f else if (cpCount <= 2) 0.120f else 0.090f
        }
        val baseAlpha = (alphaScale * 255).toInt().coerceIn(0, 255)
        val argb = when {
            isNull        -> android.graphics.Color.argb((46 * alphaScale).toInt(), 255, 255, 255)
            isHighlighted -> if (isSub) android.graphics.Color.argb(baseAlpha, 26, 10, 0)
                             else       android.graphics.Color.argb(baseAlpha, 13, 33, 55)
            isSub         -> android.graphics.Color.argb((235 * alphaScale).toInt(), 255, 220, 100)
            isInner       -> android.graphics.Color.argb((184 * alphaScale).toInt(), 255, 255, 255)
            else          -> android.graphics.Color.argb((230 * alphaScale).toInt(), 255, 255, 255)
        }
        val paint = android.graphics.Paint().apply {
            color       = argb
            textSize    = fsPx
            isAntiAlias = true
            textAlign   = android.graphics.Paint.Align.CENTER
            typeface    = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT_BOLD,
                android.graphics.Typeface.BOLD,
            )
        }
        val m = paint.fontMetrics
        canvas.nativeCanvas.drawText(label, px, py - (m.ascent + m.descent) / 2f, paint)
    }
}
