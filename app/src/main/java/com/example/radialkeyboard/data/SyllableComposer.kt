package com.example.radialkeyboard.data

import java.text.BreakIterator

/**
 * Smart syllable composer for Malayalam.
 *
 * When the last character in the buffer is a bare consonant and the
 * incoming character is a full vowel, the vowel is silently converted
 * to the correct vowel sign (matra) and fused with the consonant.
 *
 *   ക + ആ  →  കാ     (ക + U+0D3E)
 *   ക + ഇ  →  കി     (ക + U+0D3F)
 *   ക + അ  →  ക      (inherent 'a' — no sign needed)
 *
 * All other inputs are appended unchanged.
 */
object SyllableComposer {

    // Maps each full vowel to its vowel sign (matra).
    // Empty string = inherent 'a' (consonant alone already carries this sound).
    private val VOWEL_SIGN: Map<Char, String> = mapOf(
        'അ' to "",
        'ആ' to "ാ",   // ാ
        'ഇ' to "ി",   // ി
        'ഈ' to "ീ",   // ീ
        'ഉ' to "ു",   // ു
        'ഊ' to "ൂ",   // ൂ
        'ഋ' to "ൃ",   // ൃ
        'ൠ' to "ൄ",   // ൄ
        'എ' to "െ",   // െ
        'ഏ' to "േ",   // േ
        'ഐ' to "ൈ",   // ൈ
        'ഒ' to "ൊ",   // ൊ
        'ഓ' to "ോ",   // ോ
        'ഔ' to "ൗ",   // ൗ U+0D57 — modern Au length mark (U+0D4C is archaic)
    )

    private val MALAYALAM_VOWELS: Set<Char> = VOWEL_SIGN.keys

    // U+0D15–U+0D39 = ക–ഹ core consonants (have inherent 'a', take vowel signs)
    // Chillu letters (U+0D7A–U+0D7F) are excluded — they are pure consonants
    // with no inherent vowel and never take vowel signs.
    private fun isMalayalamConsonant(cp: Int): Boolean =
        cp in 0x0D15..0x0D39

    // A bare consonant = single Malayalam consonant codepoint, nothing else.
    private fun isBareConsonant(grapheme: String): Boolean {
        val cps = grapheme.codePoints().toArray()
        return cps.size == 1 && isMalayalamConsonant(cps[0])
    }

    private fun lastGrapheme(text: String): String {
        if (text.isEmpty()) return ""
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(text)
        val end   = bi.last()
        val start = bi.previous()
        return if (start == BreakIterator.DONE) text else text.substring(start, end)
    }

    private fun dropLastGrapheme(text: String): String {
        if (text.isEmpty()) return text
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(text)
        bi.last()
        val prev = bi.previous()
        return if (prev == BreakIterator.DONE) "" else text.substring(0, prev)
    }

    /**
     * Returns the new buffer string after appending [newChars] to [current],
     * applying vowel-sign fusion where applicable.
     *
     * Works for both single vowels ("ആ" → ാ) and vowel-prefixed strings
     * ("അം" → ം, because 'അ' is the inherent vowel and ം follows).
     */
    fun compose(current: String, newChars: String): String {
        if (newChars.isEmpty()) return current

        val firstChar = newChars[0]
        if (firstChar in MALAYALAM_VOWELS) {
            val lastG = lastGrapheme(current)
            if (isBareConsonant(lastG)) {
                val sign = VOWEL_SIGN[firstChar]!!
                val base = dropLastGrapheme(current)
                val tail = newChars.drop(1)   // e.g. "ം" from "അം"
                // inherent 'a': no vowel sign, just append tail (e.g. ം)
                return if (sign.isEmpty()) base + lastG + tail
                       else base + lastG + sign + tail
            }
        }

        return current + newChars
    }
}
