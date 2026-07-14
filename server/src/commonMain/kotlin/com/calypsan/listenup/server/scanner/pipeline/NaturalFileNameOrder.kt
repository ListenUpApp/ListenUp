package com.calypsan.listenup.server.scanner.pipeline

/**
 * Natural (numeric-aware, case-insensitive) string order for filename
 * tiebreaks. Digit runs compare by numeric value, non-digit runs compare
 * case-insensitively, so `"1984 - 2.mp3"` sorts before `"1984 - 10.mp3"`
 * rather than the plain UTF-16 lexicographic order that places `"10"` before
 * `"2"` (and would silently corrupt a book's playback order).
 *
 * A digit run's numeric value wins first; equal values fall back to the raw
 * run length so `"01"` and `"1"` remain deterministically ordered.
 */
internal object NaturalFileNameOrder : Comparator<String> {
    override fun compare(
        left: String,
        right: String,
    ): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            if (left[i].isDigit() && right[j].isDigit()) {
                val lEnd = digitRunEnd(left, i)
                val rEnd = digitRunEnd(right, j)
                val cmp = compareNumericRun(left.substring(i, lEnd), right.substring(j, rEnd))
                if (cmp != 0) return cmp
                i = lEnd
                j = rEnd
            } else {
                val cmp = left[i].lowercaseChar().compareTo(right[j].lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return left.length - i - (right.length - j)
    }

    /** Index one past the maximal digit run starting at [start] in [s]. */
    private fun digitRunEnd(
        s: String,
        start: Int,
    ): Int {
        var i = start
        while (i < s.length && s[i].isDigit()) i++
        return i
    }

    /**
     * Compares two digit runs by numeric value: fewer significant digits (after stripping leading
     * zeros) is smaller; equal magnitudes fall back to raw width so `"01"` and `"1"` stay
     * deterministically ordered.
     */
    private fun compareNumericRun(
        left: String,
        right: String,
    ): Int {
        val leftDigits = left.trimStart('0')
        val rightDigits = right.trimStart('0')
        if (leftDigits.length != rightDigits.length) return leftDigits.length - rightDigits.length
        val magnitude = leftDigits.compareTo(rightDigits)
        return if (magnitude != 0) magnitude else left.length - right.length
    }
}
