package com.calypsan.listenup.client.core

/**
 * The first sequence number that is *not* a series starter. Everything below it — a prequel at 0 or
 * 0.5, book 1, a 1.5 interquel — is somewhere a new listener can reasonably begin.
 */
private const val FIRST_MID_SERIES_SEQUENCE = 2.0

/**
 * Returns `true` when [sequence] marks a series starter (or prequel) — the heuristic that keeps
 * discovery to standalones and first-in-series entries.
 *
 * An unknown position returns `true`: discovery includes rather than hides, so a book with no
 * sequence still surfaces.
 *
 * This used to pick the number out of a string by hand — find the first digit, strip leading zeros,
 * check whether the next character was a `.` or a space — because the sequence was stored as text.
 * It is a number now, so the whole heuristic is the comparison it was always approximating.
 * The behaviour is unchanged: `0`, `0.5`, `1`, `1.0` and `1.5` were starters and still are; `2` and
 * `10` were not and still are not.
 *
 * @param sequence The book↔series position (e.g. `1.0`, `2.5`), or `null` when unnumbered.
 */
internal fun isFirstInSeries(sequence: Double?): Boolean = sequence == null || sequence < FIRST_MID_SERIES_SEQUENCE

/**
 * Renders a series position for display: `1.0` reads as `"1"`, `1.5` stays `"1.5"`.
 *
 * The stored value is a number, but a whole number printed as a `Double` is `"1.0"`, and
 * "Mistborn #1.0" is not how anyone writes it. This is the only place that conversion happens, and
 * it happens at the display edge — the direction that used to run through the data layer, where a
 * best-effort rendering then had to be reconciled with the server's spelling.
 */
fun formatSeriesSequence(sequence: Double): String =
    if (sequence % 1.0 == 0.0) sequence.toLong().toString() else sequence.toString()
