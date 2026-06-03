package com.calypsan.listenup.client.util

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Calculate playback progress for a list of books.
 *
 * Returns a map of bookId -> progress (0.0-1.0) for books that are
 * in progress (not unstarted, not complete).
 *
 * @param books The books to calculate progress for
 * @param excludeComplete If true (default), excludes books that are >99% complete
 * @param excludeUnstarted If true (default), excludes books with no progress
 * @return Map of bookId to progress fraction
 */
suspend fun PlaybackPositionRepository.calculateProgressMap(
    books: List<BookListItem>,
    excludeComplete: Boolean = true,
    excludeUnstarted: Boolean = true,
): Map<BookId, Float> =
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
            if (position != null && book.duration > 0) {
                val progress = (position.positionMs.toFloat() / book.duration).coerceIn(0f, 1f)
                val isInProgress =
                    (!excludeUnstarted || progress > 0f) &&
                        (!excludeComplete || progress < 0.99f)
                if (isInProgress) book.id to progress else null
            } else {
                null
            }
        }.toMap()
