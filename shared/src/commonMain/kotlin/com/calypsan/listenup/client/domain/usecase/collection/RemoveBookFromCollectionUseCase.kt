package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Removes a book from a collection.
 *
 * Removes the book via server API. The server will emit SSE events
 * to update the collection's book count.
 *
 * Usage:
 * ```kotlin
 * val result = removeBookFromCollectionUseCase(collectionId = "col1", bookId = "book1")
 * when (result) {
 *     is Success -> showSuccess()
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class RemoveBookFromCollectionUseCase(
    private val collectionRepository: CollectionRepository,
) {
    /**
     * Remove a book from a collection.
     *
     * @param collectionId The collection to remove from
     * @param bookId The book to remove
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit> {
        logger.info { "Removing book $bookId from collection $collectionId" }

        return collectionRepository.removeBookFromCollection(collectionId, bookId)
    }
}
