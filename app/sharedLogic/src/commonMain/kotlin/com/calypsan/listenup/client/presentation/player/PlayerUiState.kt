package com.calypsan.listenup.client.presentation.player

import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState

/** What the player surfaces render: one value for the mini player, Now Playing and the reader strip (spec §4.2). */
sealed interface PlayerUiState {
    /** Nothing is loaded: no mini player; Home shows its Resume hero. */
    data object NothingLoaded : PlayerUiState

    /** A book is loaded (§3.4). Never re-emitted on a position tick; ticks are [PlayerProgress]. */
    data class Loaded(
        val book: PlayerBook,
        val phase: PlayerPhase,
        /** Never null: a chapterless book is one whole-book chapter (§3.2). */
        val chapter: PlayerChapter,
        val chapters: List<PlayerChapterRow>,
        val source: AudioSource,
        val settings: PlayerSettings,
        val sleepTimer: SleepTimerState,
        val primary: PrimaryControl,
        /** §3.5 "Back N by state". */
        val showsSkipBack: Boolean,
        /** False at the last chapter (§3.3). */
        val canSkipToNextChapter: Boolean,
        /** Non-null only in [PlayerPhase.Finished]. */
        val finished: FinishedInfo?,
    ) : PlayerUiState {
        /**
         * False in a chapterless book, where previous would only jump to 0:00 (§3.3); true in every chapter of a
         * chaptered one, chapter 1 included, where it restarts the chapter. The same answer as
         * [com.calypsan.listenup.client.playback.ChapterNavigation.hasPrevious], read off the chapter every surface
         * already has.
         */
        val canSkipToPreviousChapter: Boolean get() = !chapter.isWholeBook
    }
}

/** The loaded book's identity, from Room, so it is right before the prepare returns (§3.6). */
data class PlayerBook(
    val id: String,
    val title: String,
    val authors: List<PlayerContributor>,
    val narrators: List<PlayerContributor>,
    val coverPath: String?,
    val coverHash: String?,
    /** Every series membership, in the book's order; empty when it is in none. Surfaces that show one read the first. */
    val series: List<PlayerSeriesRef>,
    val durationMs: Long,
    /** The first PDF document's id, for Open PDF, or null. */
    val firstPdfDocId: String?,
)

/** A person on the byline, with how many books they have in that role (the picker's counts, verify c6). */
data class PlayerContributor(
    val id: String,
    val name: String,
    val bookCount: Int,
)

/** One series membership: where Go to series goes and the book's place there ("1", "2.5"). */
data class PlayerSeriesRef(
    val id: String,
    val name: String,
    val sequenceLabel: String?,
)

/**
 * The current chapter. [number] is 1-based. A chapterless book is chapter 1 of 1 with [isWholeBook] true and an
 * empty [title]; surfaces say "Whole book" there (§3.2).
 */
data class PlayerChapter(
    val index: Int,
    val number: Int,
    val total: Int,
    val title: String,
    val isGenericTitle: Boolean,
    val isWholeBook: Boolean,
    val startMs: Long,
    val durationMs: Long,
)

/** One row of the chapter list. Exactly one row has [isCurrent], and it is [PlayerUiState.Loaded.chapter]. */
data class PlayerChapterRow(
    val index: Int,
    val number: Int,
    val title: String,
    val startMs: Long,
    val durationMs: Long,
    val isCurrent: Boolean,
)

/** This book's settings and the user's defaults. */
data class PlayerSettings(
    val speed: Float,
    val defaultSpeed: Float,
    val boostDb: Float,
    val defaultBoostDb: Float,
    val skipBackSec: Int,
    val skipForwardSec: Int,
)

/**
 * The finished state's facts (§3.7). Rating is in half stars, 2..10, as ratings are stored (Decision 8).
 */
data class FinishedInfo(
    val finishedAtMs: Long?,
    val startedAtMs: Long?,
    val durationMs: Long,
    val yourHalfStars: Int?,
    val nextBook: NextInSeries?,
)
