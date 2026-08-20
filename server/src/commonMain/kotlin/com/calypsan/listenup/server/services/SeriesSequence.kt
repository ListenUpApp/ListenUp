package com.calypsan.listenup.server.services

/**
 * A leading number, optionally fractional: `1`, `06`, `1.5`. Anchored at the start, so it reads a
 * numeric *prefix* rather than requiring the whole string to be a number — that is what lets an
 * omnibus label like `"1-3"` file at book 1 instead of being thrown away.
 */
private val LEADING_NUMBER = Regex("""^\d+(\.\d+)?""")

/**
 * Parses a free-form series-sequence label into the number the model stores, or `null` when the
 * label carries no usable number.
 *
 * Series text arrives free-form and stays that way at the edge — audio tags, scanner output and
 * metadata-provider responses all keep their `String?`, because that is what the source literally
 * said. This is the single point where that text becomes the `Double?` the database holds, and it
 * is called from every persist path so the conversion cannot vary by route.
 *
 * **This must agree with `V62__series_sequence_numeric.sql`.** The migration converts the text
 * already in the database; this converts the text arriving after it. If the two disagree, a rescan
 * silently overwrites a value the migration preserved. `SeriesSequenceTest` is what holds them
 * together, case by case.
 *
 * Two rules, both inherited from that SQL:
 *
 * - **A leading numeric prefix wins.** `"1-3"` and `"1 Parts 1-2"` become `1.0`, matching SQLite's
 *   `CAST`. A plain [String.toDoubleOrNull] returns `null` for both — and using it is precisely the
 *   bug this replaces, because the old save path discarded those labels without saying so.
 * - **Anything not starting with a digit is refused, never coerced.** A bare `CAST` turns
 *   `"Prequel"` into `0.0`, filing an unnumbered volume as book 0 — ahead of book 1 in every list.
 *   A wrong number is worse than no number, so this returns `null` instead.
 */
fun parseSeriesSequence(label: String?): Double? {
    val trimmed = label?.trim().orEmpty()
    if (trimmed.isEmpty() || !trimmed.first().isDigit()) return null
    return LEADING_NUMBER.find(trimmed)?.value?.toDoubleOrNull()
}
