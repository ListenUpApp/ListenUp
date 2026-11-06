package com.calypsan.listenup.client.presentation.player

/** What an always-visible chapter list does when the current chapter changes. */
sealed interface ChapterListMove {
    /** Centre the new current row. */
    data object Recentre : ChapterListMove

    /** Stay where the reader scrolled; "Back to current chapter" is offered instead. */
    data object Hold : ChapterListMove
}

/**
 * The rule for an always-visible chapter list (the web page, and the Android tablet and iPad panels): it
 * opens centred on the current chapter and follows the playhead, but never yanks the list out from under a reader who
 * scrolled away. An object, so Swift Export exposes it as `ChapterListFollow.shared`.
 */
object ChapterListFollow {
    /** The move when the current chapter changes, given whether the previous current row was fully visible. Pure. */
    fun onCurrentChanged(previousRowFullyVisible: Boolean): ChapterListMove =
        if (previousRowFullyVisible) ChapterListMove.Recentre else ChapterListMove.Hold

    /** Whether "Back to current chapter" shows: exactly while the current row is not fully visible. Pure. */
    fun showsBackToCurrent(currentRowFullyVisible: Boolean): Boolean = !currentRowFullyVisible
}
