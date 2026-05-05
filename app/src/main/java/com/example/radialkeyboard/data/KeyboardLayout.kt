package com.example.radialkeyboard.data

/**
 * Dual-ring Malayalam keyboard layout.
 *
 * Inner ring (10 segments): consonant families with vargam sub-menus.
 * Outer ring (10 segments): vowels and special chars with paired sub-menus.
 *
 * Root lists are List<RingKey?> so they share the same type as sub-menu
 * rings which may have null (empty) slots.
 */
data class RingKey(
    val label: String,
    val chars: String = label,
    val subChars: List<String> = emptyList(),
) {
    val hasSub: Boolean get() = subChars.isNotEmpty()
}

object DualRingLayout {

    const val N      = 10
    const val SWEEP  = 36f   // 360 / N

    /** Inner ring — clockwise from top (index 0 = top = -90°). */
    val innerRoot: List<RingKey?> = listOf(
        RingKey("ക",    "ക",    listOf("ക","ഖ","ഗ","ഘ","ങ")),
        RingKey("ച",    "ച",    listOf("ച","ഛ","ജ","ഝ","ഞ")),
        RingKey("ട",    "ട",    listOf("ട","ഠ","ഡ","ഢ","ണ")),
        RingKey("ത",    "ത",    listOf("ത","ഥ","ദ","ധ","ന")),
        RingKey("പ",    "പ",    listOf("പ","ഫ","ബ","ഭ","മ")),
        RingKey("യ",    "യ",    listOf("യ","ര","ല","വ")),
        RingKey("ശ",    "ശ",    listOf("ശ","ഷ","സ","ഹ","ള","ഴ","റ")),
        RingKey("ൻ",    "ൻ",    listOf("ൻ","ർ","ൽ","ൾ","ൺ")),
        RingKey("ച്ച",  "ച്ച",  listOf("ക്ക","ച്ച","ട്ട","ത്ത","പ്പ","ന്ന")),
        RingKey("ല്ല",  "ല്ല",  listOf("ല്ല","ള്ള","മ്മ","ർ","ൽ","ൾ")),
    )

    /** Outer ring — clockwise from top. */
    val outerRoot: List<RingKey?> = listOf(
        RingKey("അ",    "അ",    listOf("അ","ആ")),
        RingKey("ഇ",    "ഇ",    listOf("ഇ","ഈ")),
        RingKey("ഉ",    "ഉ",    listOf("ഉ","ഊ")),
        RingKey("ഋ",    "ഋ",    listOf("ഋ","ൠ")),
        RingKey("എ",    "എ",    listOf("എ","ഏ","ഐ")),
        RingKey("ഒ",    "ഒ",    listOf("ഒ","ഓ","ഔ")),
        RingKey("അം",   "അം",   listOf("ം","ഃ","ഁ","്")),  // ഁ = U+0D01 Candrabindu
        RingKey("്",    "്",    listOf("ൺ","ൻ","ർ","ൽ","ൾ")),
        RingKey("123",  "1",    listOf("1","2","3","4","5","6","7","8","9","0")),
        RingKey("&",    "&",    listOf("&","@","#","!","?",".",",","\"","'","_")),
    )

    /**
     * Build a 10-slot ring from a partial char list.
     * Positions beyond the list length become null (displayed as "–").
     */
    fun buildSubRing(chars: List<String>): List<RingKey?> =
        List(N) { i -> chars.getOrNull(i)?.let { RingKey(label = it, chars = it) } }
}
