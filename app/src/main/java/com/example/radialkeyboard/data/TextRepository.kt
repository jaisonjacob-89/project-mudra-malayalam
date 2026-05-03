package com.example.radialkeyboard.data

class TextRepository {

    private val _buffer = StringBuilder()

    val text: String get() = _buffer.toString()

    fun append(char: Char) {
        if (char == '\b') deleteLastGrapheme() else _buffer.append(char)
    }

    fun append(text: String) {
        val composed = SyllableComposer.compose(_buffer.toString(), text)
        _buffer.clear()
        _buffer.append(composed)
    }

    /** Delete the last grapheme cluster (handles multi-codepoint conjuncts like ച്ച). */
    fun deleteLastGrapheme() {
        if (_buffer.isEmpty()) return
        val s  = _buffer.toString()
        val bi = java.text.BreakIterator.getCharacterInstance()
        bi.setText(s)
        bi.last()
        val prev = bi.previous()
        if (prev != java.text.BreakIterator.DONE) {
            _buffer.delete(prev, s.length)
        }
    }

    fun setText(text: String) {
        _buffer.clear()
        _buffer.append(text)
    }

    fun clear() { _buffer.clear() }
}
