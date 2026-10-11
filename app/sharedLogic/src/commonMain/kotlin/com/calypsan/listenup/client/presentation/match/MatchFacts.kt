package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.dto.match.EditionFormat

/** More narrators than this are named as a "Full cast" rather than listed. */
private const val FULL_CAST_ABOVE = 2

/** What the narrator slot of a candidate line says. */
enum class NarratorCredit {
    /** The narrators' names, comma-separated. */
    Names,

    /** "Full cast". */
    FullCast,

    /** Nothing: no narrator is known. */
    None,
}

/**
 * The one rule for a candidate's narrator slot, so Android, iOS and web say the same thing.
 *
 * One or two names are listed; more than two read as a full cast. A dramatized edition that lists nobody
 * is a full-cast production, and saying nothing would read as "narrator unknown".
 */
fun narratorCredit(
    narrators: List<String>,
    format: EditionFormat?,
): NarratorCredit =
    when {
        narrators.size > FULL_CAST_ABOVE -> NarratorCredit.FullCast
        narrators.isNotEmpty() -> NarratorCredit.Names
        format == EditionFormat.DRAMATIZED -> NarratorCredit.FullCast
        else -> NarratorCredit.None
    }

/**
 * Whether a candidate line names its edition format. It does not when there is none to name, nor when the
 * narrator slot already says "Full cast" only because the edition is dramatized (saying both repeats itself).
 */
fun showsEditionFormat(
    narrators: List<String>,
    format: EditionFormat?,
): Boolean = format != null && !(narrators.isEmpty() && format == EditionFormat.DRAMATIZED)
