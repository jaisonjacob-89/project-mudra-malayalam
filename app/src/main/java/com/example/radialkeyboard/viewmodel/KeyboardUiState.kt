package com.example.radialkeyboard.viewmodel

import androidx.compose.ui.geometry.Offset
import com.example.radialkeyboard.data.DualRingLayout
import com.example.radialkeyboard.data.RingKey

enum class HapticType { SEGMENT_ENTRY, KEYBOARD_APPEAR, CHARACTER_COMMIT }

enum class Ring { INNER, OUTER }

data class KeyboardUiState(
    val typedText: String = "",
    val isKeyboardVisible: Boolean = false,
    val keyboardCenter: Offset = Offset.Zero,

    // Current segments — may be root or sub-menu; null slots = empty positions
    val innerSegs: List<RingKey?> = DualRingLayout.innerRoot,
    val outerSegs: List<RingKey?> = DualRingLayout.outerRoot,
    val innerSub: Boolean = false,
    val outerSub: Boolean = false,

    // Currently highlighted segment
    val highlightedRing: Ring? = null,
    val highlightedIdx: Int = -1,
)
