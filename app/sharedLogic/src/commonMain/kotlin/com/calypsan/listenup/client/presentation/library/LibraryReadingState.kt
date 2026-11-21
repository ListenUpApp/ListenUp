package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.domain.BookProgress

/** A book's coarse reading state. Internal: the coarse snapshot exists to gate the content revision. */
internal enum class ReadingState { NOT_STARTED, IN_PROGRESS, FINISHED }

/** The progress of a book of [durationMs] at [position], by the one rule. */
private fun progressOf(
    position: PlaybackPosition?,
    durationMs: Long,
): BookProgress = BookProgress.of(position?.positionMs, durationMs, position?.isFinished == true)

/** The coarse state of a book of [durationMs] at [position]: [BookProgress], without the numbers. */
internal fun readingStateOf(
    position: PlaybackPosition?,
    durationMs: Long,
): ReadingState =
    when (progressOf(position, durationMs)) {
        BookProgress.NotStarted -> ReadingState.NOT_STARTED
        is BookProgress.InProgress -> ReadingState.IN_PROGRESS
        BookProgress.Finished -> ReadingState.FINISHED
    }

/** One coarse state per book. Data-class equality lets `distinctUntilChanged` drop position ticks. */
internal fun readingStates(
    books: List<BookListItem>,
    positions: Map<BookId, PlaybackPosition>,
): Map<BookId, ReadingState> = books.associate { it.id to readingStateOf(positions[it.id], it.duration) }

internal fun countsOf(states: Collection<ReadingState>): BookStatusCounts =
    BookStatusCounts(
        all = states.size,
        inProgress = states.count { it == ReadingState.IN_PROGRESS },
        notStarted = states.count { it == ReadingState.NOT_STARTED },
        finished = states.count { it == ReadingState.FINISHED },
    )

internal fun BookStatusFilter.admits(state: ReadingState): Boolean =
    when (this) {
        BookStatusFilter.ALL -> true
        BookStatusFilter.IN_PROGRESS -> state == ReadingState.IN_PROGRESS
        BookStatusFilter.NOT_STARTED -> state == ReadingState.NOT_STARTED
        BookStatusFilter.FINISHED -> state == ReadingState.FINISHED
    }

internal fun cardStatusOf(
    book: BookListItem,
    position: PlaybackPosition?,
): BookCardStatus =
    when (val progress = progressOf(position, book.duration)) {
        BookProgress.NotStarted -> BookCardStatus.NotStarted(durationMs = book.duration)
        is BookProgress.InProgress -> BookCardStatus.InProgress(progress)
        BookProgress.Finished -> BookCardStatus.Finished(durationMs = book.duration)
    }
