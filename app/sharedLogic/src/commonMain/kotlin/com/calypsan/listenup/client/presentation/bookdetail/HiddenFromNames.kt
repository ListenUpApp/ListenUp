package com.calypsan.listenup.client.presentation.bookdetail

/**
 * What a "Hidden from" line shows: [shown] names, then "and [othersCount] others" when that is
 * above zero. Platforms only join and localize it.
 *
 * @property shown The names to print, already sorted by the classifier.
 * @property othersCount How many further names the line counts instead of printing.
 */
data class HiddenFromSummary(
    val shown: List<String>,
    val othersCount: Int,
)

/**
 * The one rule for long "Hidden from" lists (spec §7): three names, then a count, with Show all
 * revealing the rest. Shared so Android, iOS and web cannot cut the list at different places.
 */
object HiddenFromNames {
    /** How many names a collapsed line prints. */
    const val VISIBLE_NAMES = 3

    /** The names to print for [names], collapsed unless [expanded]. */
    fun summarize(
        names: List<String>,
        expanded: Boolean,
    ): HiddenFromSummary =
        if (expanded || names.size <= VISIBLE_NAMES) {
            HiddenFromSummary(names, othersCount = 0)
        } else {
            HiddenFromSummary(names.take(VISIBLE_NAMES), othersCount = names.size - VISIBLE_NAMES)
        }

    /** Whether "Show all" has anything left to reveal. */
    fun canExpand(
        names: List<String>,
        expanded: Boolean,
    ): Boolean = !expanded && names.size > VISIBLE_NAMES
}
