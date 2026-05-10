package com.example.radialkeyboard.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.radialkeyboard.data.DualRingLayout
import com.example.radialkeyboard.data.ML_WORDS
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
            is KeyboardEvent.KeyboardShown        -> handleKeyboardShown(event)
            is KeyboardEvent.KeyboardHidden       -> handleKeyboardHidden()
            is KeyboardEvent.SegmentHighlighted   -> handleSegmentHighlighted(event)
            is KeyboardEvent.SegmentCleared       -> handleSegmentCleared()
            is KeyboardEvent.StringCommitted      -> handleStringCommitted(event)
            is KeyboardEvent.DeleteLast           -> handleDeleteLast()
            is KeyboardEvent.SpacePressed         -> handleStringCommitted(KeyboardEvent.StringCommitted(" "))
            is KeyboardEvent.SendPressed          -> handleSendPressed()
            is KeyboardEvent.TextReplaced         -> handleTextReplaced(event)
            is KeyboardEvent.SubMenuFired         -> handleSubMenuFired(event)
            is KeyboardEvent.ResetRings           -> handleResetRings()
            is KeyboardEvent.SuggestionNavigated  -> handleSuggestionNavigated(event)
            is KeyboardEvent.SuggestionAccepted   -> handleSuggestionAccepted(event)
            is KeyboardEvent.BeyondDirChanged     -> _uiState.update { it.copy(beyondDir = event.dir) }
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
        _uiState.update { it.copy(isKeyboardVisible = false, highlightedRing = null, highlightedIdx = -1, beyondDir = 0) }
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
            val newText = textRepository.text
            _uiState.update { state ->
                state.copy(
                    typedText = newText,
                    innerSegs = DualRingLayout.innerRoot,
                    outerSegs = DualRingLayout.outerRoot,
                    innerSub  = false,
                    outerSub  = false,
                )
            }
            recomputeSuggestions(newText)
            emitHaptic(HapticType.CHARACTER_COMMIT)
        }
    }

    private fun handleDeleteLast() {
        viewModelScope.launch {
            bufferMutex.withLock { textRepository.deleteLastGrapheme() }
            val newText = textRepository.text
            _uiState.update { it.copy(typedText = newText) }
            recomputeSuggestions(newText)
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

    private fun handleSuggestionNavigated(event: KeyboardEvent.SuggestionNavigated) {
        val sugs = _uiState.value.suggestions
        if (sugs.isEmpty()) return
        val cur  = _uiState.value.selectedSuggestionIdx
        val next = ((cur + event.dir) + sugs.size) % sugs.size
        _uiState.update { it.copy(selectedSuggestionIdx = next) }
    }

    private fun handleSuggestionAccepted(event: KeyboardEvent.SuggestionAccepted) {
        viewModelScope.launch {
            val word    = event.word
            val current = textRepository.text
            // Replace the current word prefix with the full suggestion + space
            val lastSpace = current.lastIndexOf(' ')
            val newText   = (if (lastSpace >= 0) current.substring(0, lastSpace + 1) else "") + word + " "
            bufferMutex.withLock { textRepository.setText(newText) }
            _uiState.update { it.copy(
                typedText           = newText,
                suggestions         = emptyList(),
                selectedSuggestionIdx = -1,
                innerSegs           = DualRingLayout.innerRoot,
                outerSegs           = DualRingLayout.outerRoot,
                innerSub            = false,
                outerSub            = false,
            )}
            emitHaptic(HapticType.CHARACTER_COMMIT)
        }
    }

    private fun recomputeSuggestions(text: String) {
        val trimmed   = text.trimEnd()
        val lastSpace = trimmed.lastIndexOf(' ')
        val prefix    = if (lastSpace >= 0) trimmed.substring(lastSpace + 1) else trimmed
        val sugs      = findSuggestions(trimmed, prefix, 6)
        _uiState.update { it.copy(suggestions = sugs, selectedSuggestionIdx = -1) }
    }

    private fun findSuggestions(fullText: String, wordPrefix: String, n: Int): List<String> {
        val results = mutableListOf<String>()
        val seen    = mutableSetOf<String>()

        fun add(w: String): Boolean {
            if (seen.add(w)) results.add(w)
            return results.size >= n
        }

        // Pass 1 — full-text phrase continuation
        if (fullText.isNotEmpty()) {
            for (w in ML_WORDS) {
                if (w.startsWith(fullText) && w != fullText) { if (add(w)) return results }
            }
        }
        // Pass 2 — current word prefix
        if (wordPrefix.isNotEmpty()) {
            for (w in ML_WORDS) {
                if (w.startsWith(wordPrefix) && w.length > wordPrefix.length) { if (add(w)) return results }
            }
        }
        return results
    }

    private fun emitHaptic(type: HapticType) { _hapticEvents.tryEmit(type) }
}
