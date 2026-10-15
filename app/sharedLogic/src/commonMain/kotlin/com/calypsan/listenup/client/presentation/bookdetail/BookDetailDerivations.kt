package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.core.formatSeriesSequence
import com.calypsan.listenup.client.domain.chapter.currentChapterIndex
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.SeriesWithBooks

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
    val hasSeries: Boolean,
    val hasChapters: Boolean,
)

private val notStartedOrder =
    listOf(
        BookDetailSection.About,
        BookDetailSection.Ratings,
        BookDetailSection.Hardcover,
        BookDetailSection.Readers,
        BookDetailSection.Series,
        BookDetailSection.Chapters,
        BookDetailSection.Details,
    )

private val inProgressOrder =
    listOf(
        BookDetailSection.Chapters,
        BookDetailSection.About,
        BookDetailSection.Series,
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
        BookDetailSection.Series,
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
            BookDetailSection.Series -> presence.hasSeries

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
 * The series shelf for the book [thisBookId] from its first series membership's [series], or null when the book is
 * in no series or the series does not list it yet (a sync in flight). Pure.
 */
internal fun seriesShelfOf(
    series: SeriesWithBooks?,
    thisBookId: String,
): SeriesShelf? {
    if (series == null) return null
    val books = series.booksSortedBySequence()
    val thisIndex = books.indexOfFirst { it.id.value == thisBookId }
    if (thisIndex < 0) return null
    return SeriesShelf(
        seriesId = series.series.id.value,
        name = series.series.name,
        books =
            books.map { book ->
                SeriesShelfBook(
                    id = book.id.value,
                    coverPath = book.coverPath,
                    coverHash = book.coverHash,
                    sequenceLabel = series.sequenceFor(book.id.value)?.let(::formatSeriesSequence),
                    isThis = book.id.value == thisBookId,
                )
            },
        thisIndex = thisIndex,
        nextBookId = series.bookAfter(thisBookId)?.run { id.value },
    )
}
