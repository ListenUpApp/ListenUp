package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.validationError
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Reorders the books in a shelf.
 *
 * Only the shelf owner can reorder books. [orderedBookIds] must be the full
 * new ordering of the shelf's live members; an empty list is rejected.
 *
 * Usage:
 * ```kotlin
 * val result = reorderShelfBooksUseCase(
 *     shelfId = "shelf-123",
 *     orderedBookIds = listOf("book-2", "book-1", "book-3"),
 * )
 * ```
 */
open class ReorderShelfBooksUseCase(
    private val shelfRepository: ShelfRepository,
) {
    /**
     * Reorder the books in a shelf.
     *
     * @param shelfId The shelf to reorder
     * @param orderedBookIds The books in their new order (must not be empty)
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(
        shelfId: ShelfId,
        orderedBookIds: List<BookId>,
    ): AppResult<Unit> {
        if (orderedBookIds.isEmpty()) {
            return validationError("Cannot reorder an empty shelf")
        }

        logger.info { "Reordering ${orderedBookIds.size} books in shelf $shelfId" }

        return shelfRepository.reorderBooks(shelfId, orderedBookIds)
    }
}
