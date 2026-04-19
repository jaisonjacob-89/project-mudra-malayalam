package com.example.radialkeyboard.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.radialkeyboard.data.DualRingLayout
import com.example.radialkeyboard.data.TextRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KeyboardViewModel(
    private val textRepository: TextRepository = TextRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(KeyboardUiState())
    val uiState: StateFlow<KeyboardUiState> = _uiState.asStateFlow()

    private val _hapticEvents = MutableSharedFlow<HapticType>(replay = 0, extraBufferCapacity = 8)
    val hapticEvents: SharedFlow<HapticType> = _hapticEvents.asSharedFlow()

    private val _sentMessages = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 16)
    val sentMessages: SharedFlow<String> = _sentMessages.asSharedFlow()

    private val bufferMutex = Mutex()

    fun onEvent(event: KeyboardEvent) {
        when (event) {
            is KeyboardEvent.KeyboardShown      -> handleKeyboardShown(event)
            is KeyboardEvent.KeyboardHidden     -> handleKeyboardHidden()
            is KeyboardEvent.SegmentHighlighted -> handleSegmentHighlighted(event)
            is KeyboardEvent.SegmentCleared     -> handleSegmentCleared()
            is KeyboardEvent.StringCommitted    -> handleStringCommitted(event)
            is KeyboardEvent.DeleteLast         -> handleDeleteLast()
            is KeyboardEvent.SpacePressed       -> handleStringCommitted(KeyboardEvent.StringCommitted(" "))
            is KeyboardEvent.SendPressed        -> handleSendPressed()
            is KeyboardEvent.TextReplaced       -> handleTextReplaced(event)
            is KeyboardEvent.SubMenuFired       -> handleSubMenuFired(event)
            is KeyboardEvent.ResetRings         -> handleResetRings()
        }
    }

    private fun handleKeyboardShown(event: KeyboardEvent.KeyboardShown) {
        _uiState.update {
            it.copy(
                isKeyboardVisible = true,
                keyboardCenter    = event.center,
                innerSegs         = DualRingLayout.innerRoot,
                outerSegs         = DualRingLayout.outerRoot,
                innerSub          = false,
                outerSub          = false,
                highlightedRing   = null,
                highlightedIdx    = -1,
            )
        }
        emitHaptic(HapticType.KEYBOARD_APPEAR)
    }

    private fun handleKeyboardHidden() {
        _uiState.update { it.copy(isKeyboardVisible = false, highlightedRing = null, highlightedIdx = -1) }
    }

    private fun handleSegmentHighlighted(event: KeyboardEvent.SegmentHighlighted) {
        val cur     = _uiState.value
        val changed = cur.highlightedRing != event.ring || cur.highlightedIdx != event.index
        _uiState.update { it.copy(highlightedRing = event.ring, highlightedIdx = event.index) }
        if (changed) emitHaptic(HapticType.SEGMENT_ENTRY)
    }

    private fun handleSegmentCleared() {
        _uiState.update { it.copy(highlightedRing = null, highlightedIdx = -1) }
    }

    private fun handleStringCommitted(event: KeyboardEvent.StringCommitted) {
        viewModelScope.launch {
            bufferMutex.withLock { textRepository.append(event.chars) }
            _uiState.update { state ->
                state.copy(
                    typedText = textRepository.text,
                    innerSegs = DualRingLayout.innerRoot,
                    outerSegs = DualRingLayout.outerRoot,
                    innerSub  = false,
                    outerSub  = false,
                )
            }
            emitHaptic(HapticType.CHARACTER_COMMIT)
        }
    }

    private fun handleDeleteLast() {
        viewModelScope.launch {
            bufferMutex.withLock { textRepository.deleteLastGrapheme() }
            _uiState.update { it.copy(typedText = textRepository.text) }
        }
    }

    private fun handleSendPressed() {
        viewModelScope.launch {
            val text = _uiState.value.typedText
            if (text.isNotBlank()) {
                _sentMessages.emit(text)
                bufferMutex.withLock { textRepository.clear() }
                _uiState.update { it.copy(typedText = "") }
            }
        }
    }

    private fun handleTextReplaced(event: KeyboardEvent.TextReplaced) {
        viewModelScope.launch {
            bufferMutex.withLock { textRepository.setText(event.text) }
            _uiState.update { it.copy(typedText = textRepository.text) }
        }
    }

    private fun handleSubMenuFired(event: KeyboardEvent.SubMenuFired) {
        val state = _uiState.value
        when (event.ring) {
            Ring.INNER -> {
                val key = state.innerSegs.getOrNull(event.index) ?: return
                val built = DualRingLayout.buildSubRing(key.subChars)
                _uiState.update { it.copy(innerSegs = built, innerSub = true) }
            }
            Ring.OUTER -> {
                val key = state.outerSegs.getOrNull(event.index) ?: return
                val built = DualRingLayout.buildSubRing(key.subChars)
                _uiState.update { it.copy(outerSegs = built, outerSub = true) }
            }
        }
    }

    private fun handleResetRings() {
        _uiState.update {
            it.copy(
                innerSegs = DualRingLayout.innerRoot,
                outerSegs = DualRingLayout.outerRoot,
                innerSub  = false,
                outerSub  = false,
            )
        }
    }

    private fun emitHaptic(type: HapticType) { _hapticEvents.tryEmit(type) }
}
