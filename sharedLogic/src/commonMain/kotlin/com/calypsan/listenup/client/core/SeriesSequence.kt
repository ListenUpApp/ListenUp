package com.calypsan.listenup.client.core

/**
 * Returns `true` when [sequence] marks a series starter (or prequel) — mirrors the
 * Go `GetRandomBooks` heuristic used to keep discovery to standalones and
 * first-in-series entries.
 *
 * Blank, unknown, or non-numeric values return `true` (include rather than hide).
 * Prequel markers (`"0"`, `"0.5"`) and the `"1"` family (`"1"`, `"01"`, `"1.0"`,
 * `"1.5"`, `"Book 1"`) are starters; `"2"` and beyond are mid-series.
 *
 * @param sequence The book↔series sequence string (e.g. `"1"`, `"2.5"`, `"Book 3"`), or `null`.
 */
internal fun isFirstInSeries(sequence: String?): Boolean {
    val s = sequence?.trim().orEmpty()
    if (s.isEmpty()) return true
    if (s == "0" || s == "0.5") return true
    val idx = s.indexOfFirst { it in '0'..'9' }
    if (idx == -1) return true
    val numPart = s.substring(idx).trimStart('0')
    if (numPart.isEmpty()) return true // all zeros
    if (numPart[0] != '1') return false
    if (numPart.length == 1) return true
    return numPart[1] == '.' || numPart[1] == ' '
}
