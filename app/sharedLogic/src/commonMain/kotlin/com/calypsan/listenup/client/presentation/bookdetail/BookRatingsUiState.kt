package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.ScoreSource

/** The rating block on Book Detail. */
sealed interface BookRatingsUiState {
    /** Not read yet. */
    data object Loading : BookRatingsUiState

    /**
     * @property listeners your listeners' average, or null when nobody has rated the book.
     * @property mine the signed-in listener's rating, or null when they haven't rated it. Stars you have
     *   just set show here at once, before the save lands.
     * @property external the ListenUp score — every enabled outside catalog plus this server's listeners,
     *   calibrated over the library — or null when no source has rated the book yet. Its
     *   [CombinedScore.shares] give each source's weight for the sources view's rows (the listeners' under
     *   [ScoreSource.Listeners]) and [CombinedScore.sourceCount] the "Combined from N sources" line. The
     *   same value the library's Rating sort uses.
     * @property breakdown the per-source outside ratings backing [external], highest rating count first —
     *   the sources view, one tap away from the score's row. The listeners' row is [listeners].
     * @property canRefresh whether the signed-in listener may trigger
     *   [BookRatingsViewModel.refreshExternal] (admin or root).
     * @property isRefreshingExternal whether a [BookRatingsViewModel.refreshExternal] is still in flight —
     *   true until the server answers, whether or not any score changed.
     * @property isCheckingExternal whether the server is fetching this book's Hardcover rating right now,
     *   because Book Detail opened it — see [scoreRow].
     */
    data class Ready(
        val listeners: ListenerAverage?,
        val mine: ListenerRating?,
        val external: CombinedScore?,
        val breakdown: List<ExternalRating>,
        val canRefresh: Boolean,
        val isRefreshingExternal: Boolean = false,
        val isCheckingExternal: Boolean = false,
    ) : BookRatingsUiState {
        /** The ListenUp score's row, decided once here so every platform draws the same one. */
        val scoreRow: ScoreRow
            get() {
                // A listeners-only score would repeat the listeners' row as a second, recalibrated number.
                val score = external?.takeUnless { it.isListenersOnly }
                return when {
                    score != null -> ScoreRow.Shown(score)
                    isCheckingExternal -> ScoreRow.Checking
                    listeners == null -> ScoreRow.NoRatings
                    else -> ScoreRow.Absent
                }
            }

        /**
         * Whether the quiet "Refresh ratings" sits in the block itself. It does for an admin only when
         * there is no score row to open the sources view from, where it otherwise lives — so an admin is
         * never stranded on a book with no outside score.
         */
        val showsInlineRefresh: Boolean
            get() = canRefresh && (scoreRow == ScoreRow.NoRatings || scoreRow == ScoreRow.Absent)

        /**
         * The outside ratings the score draws on, in [breakdown]'s order — their sources name it ("Audible,
         * Hardcover"). Rows, not bare [ExternalRatingSource]s: Swift Export traps on the elements of an enum
         * list, but reads an enum property off a class element cleanly (`NoBridgedEnumCollectionsInUiStateRule`).
         */
        val outsideRatingsInScore: List<ExternalRating>
            get() = breakdown.filter { external?.run { shares.containsKey(ScoreSource.Outside(it.source)) } == true }

        /** Whether your listeners are part of the score — "…, your listeners". */
        val listenersInScore: Boolean
            get() = external?.run { shares.containsKey(ScoreSource.Listeners) } == true
    }
}

/** What the ListenUp score's row shows, in the "everyone" half of the rating block. */
sealed interface ScoreRow {
    /** The ListenUp score, which opens the sources view. */
    data class Shown(
        val score: CombinedScore,
    ) : ScoreRow

    /** Book Detail's on-open Hardcover fetch is running, with no score yet: "Checking Hardcover…" holds the row. */
    data object Checking : ScoreRow

    /** Nobody anywhere has rated the book: "No ratings yet". */
    data object NoRatings : ScoreRow

    /** Only your listeners have rated it: their own row says so, and no second number is drawn. */
    data object Absent : ScoreRow
}

/** One-shot effects of the rating block. */
sealed interface BookRatingsEvent {
    /** Your rating was removed: Android and web offer Undo; iOS tells VoiceOver. */
    data object RatingRemoved : BookRatingsEvent
}
