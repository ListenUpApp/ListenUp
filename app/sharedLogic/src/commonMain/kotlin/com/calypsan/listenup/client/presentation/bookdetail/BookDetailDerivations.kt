package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.chapter.currentChapterIndex
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.PlaybackPosition

/** Where the reader is with a book of [durationMs], from its saved [position]. Pure. */
internal fun readingStage(
    position: PlaybackPosition?,
    durationMs: Long,
): ReadingStage =
    when {
        position?.isFinished == true -> ReadingStage.Finished
        position != null && durationMs > 0 && position.positionMs > 0 -> ReadingStage.InProgress
        else -> ReadingStage.NotStarted
    }

/** Which sections the device itself knows to be absent. Readers, Ratings and Hardcover are the platform's call. */
internal data class SectionPresence(
    val hasChapters: Boolean,
)

private val notStartedOrder =
    listOf(
        BookDetailSection.About,
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Readers,
        BookDetailSection.Chapters,
        BookDetailSection.Details,
    )

private val inProgressOrder =
    listOf(
        BookDetailSection.Chapters,
        BookDetailSection.About,
        BookDetailSection.Readers,
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Details,
    )

private val finishedOrder =
    listOf(
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Readers,
        BookDetailSection.Chapters,
        BookDetailSection.About,
        BookDetailSection.Details,
    )

/**
 * The sections below the action unit, in the order [stage] puts them (spec §2.4), minus those [presence] knows are
 * absent. Pure; the ViewModel latches the answer once per visit.
 */
internal fun sectionOrderFor(
    stage: ReadingStage,
    presence: SectionPresence,
): List<BookDetailSection> {
    val order =
        when (stage) {
            ReadingStage.NotStarted -> notStartedOrder
            ReadingStage.InProgress -> inProgressOrder
            ReadingStage.Finished -> finishedOrder
        }
    return order.filter { section ->
        when (section) {
            BookDetailSection.Chapters -> presence.hasChapters

            BookDetailSection.About,
            BookDetailSection.Ratings,
            BookDetailSection.Hardcover,
            BookDetailSection.Readers,
            BookDetailSection.Details,
            -> true
        }
    }
}

/**
 * Book Detail's primary action. Disabled only for [PrimaryAction.UnavailableOffline]: the server is unreachable
 * ([showServerWarning]) and nothing of the book is on the device (![canPlay] — `canPlay` is "some audio downloaded,
 * or the server reachable").
 */
internal fun primaryActionFor(
    stage: ReadingStage,
    showServerWarning: Boolean,
    canPlay: Boolean,
): PrimaryAction =
    when {
        showServerWarning && !canPlay -> PrimaryAction.UnavailableOffline
        stage == ReadingStage.InProgress -> PrimaryAction.Resume
        stage == ReadingStage.Finished -> PrimaryAction.ListenAgain
        else -> PrimaryAction.Play
    }

/** How many chapter rows Book Detail shows collapsed (spec §2.5). */
internal const val CHAPTER_WINDOW_SIZE = 5

/** The chapter [positionMs] sits in, or null when there are no chapters. Pure. */
internal fun currentChapterOf(
    chapters: List<Chapter>,
    positionMs: Long,
): CurrentChapter? {
    val index = currentChapterIndex(chapters.map { it.startTime }, positionMs) ?: return null
    val chapter = chapters[index]
    return CurrentChapter(
        index = index,
        number = index + 1,
        title = chapter.title,
        startMs = chapter.startTime,
        elapsedInChapterMs = (positionMs - chapter.startTime).coerceAtLeast(0L),
    )
}

/**
 * The collapsed chapter list: five rows around [currentIndex] (two either side, clamped at the ends), or the first
 * five when there is no current chapter (not started, finished). Pure.
 */
internal fun chapterWindow(
    rows: List<ChapterUiModel>,
    currentIndex: Int?,
): List<ChapterUiModel> {
    if (rows.size <= CHAPTER_WINDOW_SIZE) return rows
    val start =
        if (currentIndex == null) {
            0
        } else {
            (currentIndex - CHAPTER_WINDOW_SIZE / 2).coerceIn(0, rows.size - CHAPTER_WINDOW_SIZE)
        }
    return rows.subList(start, start + CHAPTER_WINDOW_SIZE)
}

/**
 * What Book Detail shows of a playhead at [positionMs]: the chapter, whole minutes into it, into the book and left.
 * Two playheads with the same answer draw the same page, so the live playhead only rebuilds the page when this moves.
 */
internal data class VisiblePlayhead(
    val chapterIndex: Int,
    val minutesIntoChapter: Long,
    val minutesIntoBook: Long,
    val minutesLeft: Long,
)

/** [VisiblePlayhead] at [positionMs] over [chapters]. Pure. */
internal fun visiblePlayhead(
    positionMs: Long,
    chapters: List<Chapter>,
): VisiblePlayhead {
    // The same answer as [currentChapterOf], so the key moves exactly when the current chapter does.
    val index = currentChapterIndex(chapters.map { it.startTime }, positionMs) ?: -1
    val chapterStart = chapters.getOrNull(index)?.startTime ?: 0L
    val end = chapters.lastOrNull()?.let { it.startTime + it.duration } ?: positionMs
    return VisiblePlayhead(
        chapterIndex = index,
        minutesIntoChapter = (positionMs - chapterStart) / MILLIS_PER_MINUTE,
        minutesIntoBook = positionMs / MILLIS_PER_MINUTE,
        minutesLeft = (end - positionMs).coerceAtLeast(0L) / MILLIS_PER_MINUTE,
    )
}

private const val MILLIS_PER_MINUTE = 60_000L
