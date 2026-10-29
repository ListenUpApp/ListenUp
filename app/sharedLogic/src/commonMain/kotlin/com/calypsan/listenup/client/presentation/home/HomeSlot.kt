package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.domain.model.ShelfCover

/**
 * The one slot directly under Home's greeting (spec §2.2). Its kind is chosen at a decision point and held until the
 * next (§2.1); what is inside it stays live.
 */
sealed interface HomeSlot {
    /**
     * No slot: a book is loaded (the mini player owns it), or books are in progress, which Everything in progress
     * lists (Simon, 2026-10-27: Home has no Resume hero).
     */
    data object Absent : HomeSlot

    /** The library's first sync is still arriving: a skeleton slot that resolves in place (§2.3). */
    data object FirstSync : HomeSlot

    /** A book finished in the last 72 hours that the reader has neither dismissed nor moved on from (§2.2 row 2). */
    data class JustFinished(
        val card: FinishedCard,
    ) : HomeSlot

    /**
     * Nothing is in progress but the reader has listened before.
     *
     * @property candidate the next unstarted book in a series they finished, newest finish first, or null.
     */
    data class NothingInProgress(
        val candidate: NextInSeries?,
    ) : HomeSlot

    /**
     * No listening history at all: a welcome that invites, never a count of the library.
     *
     * @property covers up to four of the newest playable books that have a cover.
     */
    data class NewListener(
        val covers: List<ShelfCover>,
    ) : HomeSlot
}

/**
 * Everything the just-finished card shows (spec §2.3 JustFinished).
 *
 * @property startedAtMs when this device saw the read start, or null.
 * @property finishedAtMs when it was finished.
 * @property yourHalfStars your rating of it on the 2..10 scale, or null when you have not rated it.
 * @property next the book after it in its series, or null — then the card has no primary button.
 * @property alsoFinished who else on the server finished it.
 */
data class FinishedCard(
    val book: FinishedBook,
    val startedAtMs: Long?,
    val finishedAtMs: Long,
    val yourHalfStars: Int?,
    val next: NextInSeries?,
    val alsoFinished: AlsoFinished,
)

/**
 * The finished book itself, as its card draws it.
 *
 * @property authorNames the authors joined with ", ".
 * @property durationMs its total length.
 */
data class FinishedBook(
    val bookId: String,
    val title: String,
    val authorNames: String,
    val coverPath: String?,
    val coverHash: String?,
    val durationMs: Long,
)

/**
 * "Maya and Tom finished it too" (spec §2.3): up to two names, and how many more. Both empty means no line.
 *
 * @property names up to two display names, most recent finish first.
 * @property othersCount everyone else who finished it, named or not.
 */
data class AlsoFinished(
    val names: List<String>,
    val othersCount: Int,
)

/** The part of the day Home greets in; each platform words it from its catalogs (H2). */
enum class TimeOfDay { Morning, Afternoon, Evening, Night }

/**
 * Whether Home can reach anything (spec §3.1 offline indicator): the device's network first, then the server. An
 * unknown reachability reads as [Online] — absence of evidence never blocks.
 */
enum class HomeConnectivity { Online, Offline, ServerUnreachable }
