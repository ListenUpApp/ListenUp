package com.calypsan.listenup.client.presentation.library

/**
 * How many entries each Library section holds, for the iPad sidebar's counts (spec §3.1.5): Books is the whole library
 * whatever the status filter; Series is the series the list shows (after "hide single-book series"). Computed in the
 * sort stage, so it moves with the content revision and never on a position tick.
 */
data class LibrarySectionCounts(
    val books: Int,
    val series: Int,
    val authors: Int,
    val narrators: Int,
)
