package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.core.BookId

/** A book's coarse reading state. Internal: the coarse snapshot exists to gate the content revision. */
internal enum class ReadingState { NOT_STARTED, IN_PROGRESS, FINISHED }

/** Finished wins; otherwise any progress above zero is in progress (spec §3.1.1 L3). */
internal fun readingStateOf(position: PlaybackPosition?): ReadingState =
    when {
        position == null -> ReadingState.NOT_STARTED
        position.isFinished -> ReadingState.FINISHED
        position.positionMs > 0L -> ReadingState.IN_PROGRESS
        else -> ReadingState.NOT_STARTED
    }

/** One coarse state per book. Data-class equality lets `distinctUntilChanged` drop position ticks. */
internal fun readingStates(
    books: List<BookListItem>,
    positions: Map<BookId, PlaybackPosition>,
): Map<BookId, ReadingState> = books.associate { it.id to readingStateOf(positions[it.id]) }

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
): BookCardStatus {
    val state = readingStateOf(position)
    return when {
        state == ReadingState.NOT_STARTED -> {
            BookCardStatus.NotStarted(durationMs = book.duration)
        }

        state == ReadingState.FINISHED -> {
            BookCardStatus.Finished(durationMs = book.duration)
        }

        // In progress always has a position; an unknown length can't say how far along it is.
        position == null || book.duration <= 0L -> {
            BookCardStatus.InProgress(fraction = 0f, timeLeftMs = 0L)
        }

        else -> {
            BookCardStatus.InProgress(
                fraction = (position.positionMs.toFloat() / book.duration).coerceIn(0f, 1f),
                timeLeftMs = (book.duration - position.positionMs).coerceAtLeast(0L),
            )
        }
    }
}
