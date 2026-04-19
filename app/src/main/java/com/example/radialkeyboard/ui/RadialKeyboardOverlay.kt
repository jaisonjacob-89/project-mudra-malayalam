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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
private const val F_SPLIT     = 0.50f
private const val F_DEAD      = 0.20f
private const val LP_MS       = 5000L
private const val WHEEL_ALPHA = 0.93f

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
    modifier: Modifier = Modifier,
) {
    val scope          = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    // Consume haptic events from ViewModel
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

    // Long-press animation state (read inside Canvas to drive redraw)
    var lpProgress by remember { mutableFloatStateOf(0f) }
    var lpRing     by remember { mutableStateOf<Ring?>(null) }
    var lpIdx      by remember { mutableIntStateOf(-1) }
    var lpJob      by remember { mutableStateOf<Job?>(null) }

    // Per-ring rotation offsets — snapped to finger's first entry angle
    // so segment 0 (ക / അ) always appears under the finger
    var innerRot by remember { mutableFloatStateOf(-90f) }
    var outerRot by remember { mutableFloatStateOf(-90f) }

    // Keep fresh references across recompositions for use inside pointerInput
    val currentUiState by rememberUpdatedState(uiState)

    // Fires sub-menu and rotates the ring so sub[0] lands where the long-pressed key was
    fun fireSubMenu(ring: Ring, idx: Int) {
        when (ring) {
            Ring.INNER -> innerRot = ((innerRot + idx * SWEEP_DEG) % 360f + 360f) % 360f
            Ring.OUTER -> outerRot = ((outerRot + idx * SWEEP_DEG) % 360f + 360f) % 360f
        }
        onHighlight(null, -1)   // clear stale highlight; next move event re-evaluates
        onSubMenuFired(ring, idx)
    }

    fun startLongPress(ring: Ring, idx: Int) {
        lpJob?.cancel()
        lpRing = ring; lpIdx = idx; lpProgress = 0f
        lpJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (true) {
                delay(16L)
                lpProgress = ((System.currentTimeMillis() - startMs) / LP_MS.toFloat())
                    .coerceIn(0f, 1f)
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
                    val down        = awaitFirstDown(requireUnconsumed = false)
                    val touchOrigin = down.position
                    // Reset rotations for this new gesture
                    innerRot = -90f; outerRot = -90f
                    var innerRotSet = false; var outerRotSet = false
                    onShow(touchOrigin)

                    val wheelPx = wheelRadiusDp.toPx()
                    var lastRing: Ring? = null
                    var lastIdx        = -1

                    try {
                        var event = awaitPointerEvent()
                        while (event.type != PointerEventType.Release &&
                               event.type != PointerEventType.Exit)
                        {
                            val pos  = event.changes.firstOrNull()?.position ?: break
                            val dx   = pos.x - touchOrigin.x
                            val dy   = pos.y - touchOrigin.y
                            val dist = sqrt(dx * dx + dy * dy)
                            val dead = wheelPx * F_DEAD
                            val split = wheelPx * F_SPLIT

                            // Compute raw angle for snapping
                            var rawDeg = (atan2(dy, dx) * (180.0 / PI)).toFloat()
                            if (rawDeg < 0) rawDeg += 360f

                            // Snap ring rotation once on first entry so segment 0 is under finger
                            if (dist >= dead && dist < split && !innerRotSet) {
                                innerRot = rawDeg; innerRotSet = true
                            }
                            if (dist >= split && dist <= wheelPx && !outerRotSet) {
                                outerRot = rawDeg; outerRotSet = true
                            }

                            val (ring, idx) = hitTestDual(touchOrigin, pos, wheelPx, innerRot, outerRot)

                            // Cancel and restart LP when moving to a new segment
                            if (ring != lpRing || idx != lpIdx) {
                                cancelLongPress()
                                if (ring != null && idx >= 0) startLongPress(ring, idx)
                            }

                            // Emit highlight only on change
                            if (ring != lastRing || idx != lastIdx) {
                                lastRing = ring; lastIdx = idx
                                onHighlight(ring, idx)
                            }

                            event.changes.forEach { it.consume() }
                            event = awaitPointerEvent()
                        }

                        // ── LIFT ─────────────────────────────────────────────
                        cancelLongPress()
                        val finalPos  = event.changes.firstOrNull()?.position ?: touchOrigin
                        val dxC       = finalPos.x - touchOrigin.x
                        val dyC       = finalPos.y - touchOrigin.y
                        val distFinal = sqrt(dxC * dxC + dyC * dyC)
                        val deadZone  = wheelPx * F_DEAD

                        when {
                            // Beyond outer ring → space (right) or backspace (left)
                            distFinal > wheelPx -> {
                                if (dxC > 0) onSpace() else onDelete()
                            }
                            // Center tap → reset sub-menus
                            distFinal < deadZone -> {
                                if (currentUiState.innerSub || currentUiState.outerSub) {
                                    innerRot = -90f; outerRot = -90f
                                    onResetRings()
                                }
                            }
                            // Inside rings → commit highlighted character
                            lastRing != null && lastIdx >= 0 -> {
                                val segs = if (lastRing == Ring.INNER)
                                    currentUiState.innerSegs
                                else
                                    currentUiState.outerSegs
                                segs.getOrNull(lastIdx)?.chars?.let { onStringCommitted(it) }
                            }
                        }
                    } finally {
                        cancelLongPress()
                        onHide()
                        onHighlight(null, -1)
                    }
                }
            }
    ) {
        if (!uiState.isKeyboardVisible && scaleAnim.value == 0f) return@Canvas

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
            val fill = when {
                seg == null -> Color(0xFF282320).copy(alpha = 0.6f)
                isHi        -> if (isSub) C_SUB_HI else C_OUTER_HI
                isSub       -> C_SUB_BASE
                else        -> C_OUTER_BASE
            }
            drawAnnulusSector(center, Rsplit, R, startDeg, SWEEP_DEG, fill.copy(alpha = WHEEL_ALPHA))
        }

        // ── INNER RING ───────────────────────────────────────────────────────
        for (i in 0 until N) {
            val startDeg = innerRot + i * SWEEP_DEG
            val isHi     = uiState.highlightedRing == Ring.INNER && uiState.highlightedIdx == i
            val isSub    = uiState.innerSub
            val seg      = uiState.innerSegs.getOrNull(i)
            val fill = when {
                seg == null -> Color(0xFF1E1C1A).copy(alpha = 0.6f)
                isHi        -> if (isSub) C_SUB_HI else C_INNER_HI
                isSub       -> C_SUB_BASE
                else        -> C_INNER_BASE
            }
            drawAnnulusSector(center, Rdead, Rsplit, startDeg, SWEEP_DEG, fill.copy(alpha = WHEEL_ALPHA))
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
                    color    = android.graphics.Color.argb(230, 255, 220, 80)
                    textSize = R * 0.085f
                    isAntiAlias = true
                    textAlign   = android.graphics.Paint.Align.CENTER
                    typeface    = android.graphics.Typeface.DEFAULT_BOLD
                }
                canvas.nativeCanvas.drawText("${secLeft}s", lx, ly + p.textSize * 0.35f, p)
            }
        }

        // ── DIVIDER LINES — inner and outer rings may have different rotations ─
        for (i in 0 until N) {
            val rad = (innerRot + i * SWEEP_DEG) * (PI / 180.0).toFloat()
            drawLine(Color.White.copy(alpha = 0.14f),
                Offset(center.x + cos(rad) * Rdead,  center.y + sin(rad) * Rdead),
                Offset(center.x + cos(rad) * Rsplit, center.y + sin(rad) * Rsplit), 1.2f)
        }
        for (i in 0 until N) {
            val rad = (outerRot + i * SWEEP_DEG) * (PI / 180.0).toFloat()
            drawLine(Color.White.copy(alpha = 0.14f),
                Offset(center.x + cos(rad) * Rsplit, center.y + sin(rad) * Rsplit),
                Offset(center.x + cos(rad) * R,      center.y + sin(rad) * R), 1.2f)
        }

        // ── RING-SPLIT CIRCLE ────────────────────────────────────────────────
        drawCircle(Color.White.copy(alpha = 0.20f), radius = Rsplit, center = center, style = Stroke(1.3f))

        // ── OUTER EDGE ───────────────────────────────────────────────────────
        drawCircle(Color.White.copy(alpha = 0.09f), radius = R,      center = center, style = Stroke(1.5f))

        // ── OUTER RING LABELS ────────────────────────────────────────────────
        val outerLabelR = Rsplit + (R - Rsplit) * 0.52f
        for (i in 0 until N) {
            val seg    = uiState.outerSegs.getOrNull(i)
            val midRad = (outerRot + i * SWEEP_DEG + SWEEP_DEG / 2f) * (PI / 180.0).toFloat()
            drawRingLabel(
                label       = seg?.label ?: "–",
                px          = center.x + cos(midRad) * outerLabelR,
                py          = center.y + sin(midRad) * outerLabelR,
                radiusPx    = R,
                isNull      = seg == null,
                isHighlighted = uiState.highlightedRing == Ring.OUTER && uiState.highlightedIdx == i,
                isSub       = uiState.outerSub,
                isInner     = false,
            )
        }

        // ── INNER RING LABELS ────────────────────────────────────────────────
        val innerLabelR = Rdead + (Rsplit - Rdead) * 0.52f
        for (i in 0 until N) {
            val seg    = uiState.innerSegs.getOrNull(i)
            val midRad = (innerRot + i * SWEEP_DEG + SWEEP_DEG / 2f) * (PI / 180.0).toFloat()
            drawRingLabel(
                label       = seg?.label ?: "–",
                px          = center.x + cos(midRad) * innerLabelR,
                py          = center.y + sin(midRad) * innerLabelR,
                radiusPx    = R,
                isNull      = seg == null,
                isHighlighted = uiState.highlightedRing == Ring.INNER && uiState.highlightedIdx == i,
                isSub       = uiState.innerSub,
                isInner     = true,
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
            val path  = Path().apply {
                annulusSectorPath(center, r1 + 1f, r2 - 1f, sDeg, SWEEP_DEG)
            }
            drawPath(path, glow, style = Stroke(2.5f))
        }

        // ── CENTER CIRCLE ────────────────────────────────────────────────────
        drawCircle(C_CENTER, radius = Rdead, center = center)
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
    // Angle relative to ring's rotation offset so index 0 is at rot
    val logical = ((deg - rot) % 360f + 360f) % 360f
    val idx     = (logical / SWEEP_DEG).toInt() % N

    return HitResult(ring, idx)
}

private fun Path.annulusSectorPath(
    center: Offset,
    innerR: Float,
    outerR: Float,
    startDeg: Float,
    sweepDeg: Float,
) {
    arcTo(
        rect                  = Rect(center.x - outerR, center.y - outerR, center.x + outerR, center.y + outerR),
        startAngleDegrees     = startDeg,
        sweepAngleDegrees     = sweepDeg,
        forceMoveTo           = true,
    )
    arcTo(
        rect                  = Rect(center.x - innerR, center.y - innerR, center.x + innerR, center.y + innerR),
        startAngleDegrees     = startDeg + sweepDeg,
        sweepAngleDegrees     = -sweepDeg,
        forceMoveTo           = false,
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
) {
    drawIntoCanvas { canvas ->
        val cpCount = label.codePointCount(0, label.length)
        val fsPx    = radiusPx * when {
            isNull   -> 0.07f
            isInner  -> if (cpCount <= 1) 0.115f else if (cpCount <= 2) 0.090f else 0.072f
            else     -> if (cpCount <= 1) 0.150f else if (cpCount <= 2) 0.120f else 0.090f
        }
        val argb = when {
            isNull        -> android.graphics.Color.argb(46, 255, 255, 255)
            isHighlighted -> if (isSub) android.graphics.Color.rgb(26, 10, 0)
                             else       android.graphics.Color.rgb(13, 33, 55)
            isSub         -> android.graphics.Color.argb(235, 255, 220, 100)
            isInner       -> android.graphics.Color.argb(184, 255, 255, 255)
            else          -> android.graphics.Color.argb(230, 255, 255, 255)
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

