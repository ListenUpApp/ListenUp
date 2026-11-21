package com.calypsan.listenup.client.util

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.domain.BookProgress
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The [books] that are in progress, each with its [BookProgress.InProgress]: started, and neither flagged finished
 * nor inside the end credits. Unstarted and finished books are left out.
 */
suspend fun PlaybackPositionRepository.calculateProgressMap(
    books: List<BookListItem>,
): Map<BookId, BookProgress.InProgress> =
    books
        .mapNotNull { book ->
            val position =
                when (val r = get(book.id)) {
                    is AppResult.Success -> {
                        r.data
                    }

                    is AppResult.Failure -> {
                        logger.warn { "calculateProgressMap: get(${book.id.value}) failed: ${r.error.message}" }
                        null
                    }
                }
            val progress = BookProgress.of(position?.positionMs, book.duration, position?.isFinished == true)
            (progress as? BookProgress.InProgress)?.let { book.id to it }
        }.toMap()
