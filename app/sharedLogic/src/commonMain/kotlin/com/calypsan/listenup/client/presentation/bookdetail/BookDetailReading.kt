package com.calypsan.listenup.client.presentation.bookdetail

/**
 * Where the reader is with a book: what Book Detail's primary action, progress unit and section order follow.
 * Live — it changes the moment the saved position does (playback finishing the book, Mark as finished, Restart).
 */
sealed interface ReadingStage {
    /** No progress and not finished: the reader is deciding. */
    data object NotStarted : ReadingStage

    /** Some progress, not finished. */
    data object InProgress : ReadingStage

    /** The saved position says finished. */
    data object Finished : ReadingStage
}

/**
 * What Book Detail's primary button does. "Preparing…" is not a value here: it is the platform player's own state.
 * A held book has one too, but the triage layout replaces the action unit, so it is never drawn.
 */
sealed interface PrimaryAction {
    /** Not started: start from the beginning. */
    data object Play : PrimaryAction

    /** In progress: carry on from the saved position. */
    data object Resume : PrimaryAction

    /** Finished: start a new listen-through. */
    data object ListenAgain : PrimaryAction

    /** The server is unreachable and nothing of this book is on the device. The only disabled state. */
    data object UnavailableOffline : PrimaryAction
}

/**
 * A section of Book Detail below the action unit. The hero, progress unit, action unit and social line never move;
 * only these reorder, by [ReadingStage], once per visit (see [BookDetailUiState.Ready.sectionOrder]). Story World
 * joins when the feature exists: its slot is after Chapters (in progress), Readers (finished) and Chapters (not
 * started).
 */
sealed interface BookDetailSection {
    /** The synopsis. */
    data object About : BookDetailSection

    /** Ratings; led by your own rating ("How was it?") once finished. */
    data object Ratings : BookDetailSection

    /** The Hardcover row. Platforms skip it when Hardcover is not connected. */
    data object Hardcover : BookDetailSection

    /** Who else is reading. Platforms skip it when nobody is. */
    data object Readers : BookDetailSection

    /** The chapter list, collapsed to [BookDetailUiState.Ready.chapterPreview]. */
    data object Chapters : BookDetailSection

    /** The details rows. */
    data object Details : BookDetailSection
}

/**
 * The chapter the saved position sits in, while the book is in progress.
 *
 * @property index Zero-based index into [BookDetailUiState.Ready.chapters].
 * @property number One-based, as a person counts ("Ch. 6").
 * @property title The chapter's own title.
 * @property startMs Where the chapter starts in the book, in milliseconds.
 * @property elapsedInChapterMs How far into the chapter the position is ("Now playing · 12 min in").
 * @property isInPlayer Whether this book is the player's loaded book: its row says "Now playing" only then, and
 *   "You're here" while another book is in the player.
 */
data class CurrentChapter(
    val index: Int,
    val number: Int,
    val title: String,
    val startMs: Long,
    val elapsedInChapterMs: Long,
    val isInPlayer: Boolean = false,
)
