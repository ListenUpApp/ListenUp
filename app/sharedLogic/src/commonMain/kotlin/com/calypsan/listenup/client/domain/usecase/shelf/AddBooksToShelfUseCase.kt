package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.validationError
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Adds books to a shelf.
 *
 * Only the shelf owner can add books to their shelf.
 * The book IDs list must not be empty.
 *
 * Usage:
 * ```kotlin
 * val result = addBooksToShelfUseCase(
 *     shelfId = "shelf-123",
 *     bookIds = listOf("book-1", "book-2")
 * )
 * ```
 */
open class AddBooksToShelfUseCase(
    private val shelfRepository: ShelfRepository,
) {
    /**
     * Add books to a shelf.
     *
     * @param shelfId The shelf to add to
     * @param bookIds The books to add (must not be empty)
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(
        shelfId: ShelfId,
        bookIds: List<BookId>,
    ): AppResult<Unit> {
        if (bookIds.isEmpty()) {
            return validationError("At least one book must be selected")
        }

        logger.info { "Adding ${bookIds.size} books to shelf $shelfId" }

        return shelfRepository.addBooksToShelf(shelfId, bookIds)
    }
}
