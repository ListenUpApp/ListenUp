package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.repository.CollectionBookSummary
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Loads books in a collection from the server.
 *
 * Fetches the list of books that belong to a collection.
 *
 * Usage:
 * ```kotlin
 * val result = loadCollectionBooksUseCase(collectionId = "123")
 * when (result) {
 *     is Success -> displayBooks(result.data)
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class LoadCollectionBooksUseCase(
    private val collectionRepository: CollectionRepository,
) {
    /**
     * Load books in a collection.
     *
     * @param collectionId The collection ID
     * @return Result containing list of book summaries or a failure
     */
    open suspend operator fun invoke(collectionId: String): AppResult<List<CollectionBookSummary>> {
        logger.debug { "Loading books for collection: $collectionId" }

        return collectionRepository.getCollectionBooks(collectionId)
    }
}
