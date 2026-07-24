package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Removes a book from a shelf.
 *
 * Only the shelf owner can remove books from their shelf.
 *
 * Usage:
 * ```kotlin
 * val result = removeBookFromShelfUseCase(
 *     shelfId = "shelf-123",
 *     bookId = "book-456"
 * )
 * ```
 */
open class RemoveBookFromShelfUseCase(
    private val shelfRepository: ShelfRepository,
) {
    /**
     * Remove a book from a shelf.
     *
     * @param shelfId The shelf to remove from
     * @param bookId The book to remove
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(
        shelfId: ShelfId,
        bookId: BookId,
    ): AppResult<Unit> {
        logger.info { "Removing book $bookId from shelf $shelfId" }
        return shelfRepository.removeBookFromShelf(shelfId, bookId)
    }
}
