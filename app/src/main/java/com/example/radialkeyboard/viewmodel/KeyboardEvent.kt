package com.example.radialkeyboard.viewmodel

import androidx.compose.ui.geometry.Offset

sealed interface KeyboardEvent {

    // Lifecycle
    data class KeyboardShown(val center: Offset) : KeyboardEvent
    data object KeyboardHidden : KeyboardEvent

    // Segment hover
    data class SegmentHighlighted(val ring: Ring, val index: Int) : KeyboardEvent
    data object SegmentCleared : KeyboardEvent

    // Text editing
    data class StringCommitted(val chars: String) : KeyboardEvent
    data object DeleteLast : KeyboardEvent
    data object SpacePressed : KeyboardEvent
    data object SendPressed : KeyboardEvent
    data class TextReplaced(val text: String) : KeyboardEvent

    // Sub-menu
    data class SubMenuFired(val ring: Ring, val index: Int) : KeyboardEvent
    data object ResetRings : KeyboardEvent
}
