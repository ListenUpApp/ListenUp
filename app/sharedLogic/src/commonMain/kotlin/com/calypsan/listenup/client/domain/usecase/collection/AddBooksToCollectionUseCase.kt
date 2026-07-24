package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.validationError
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Adds multiple books to a single collection. Admin-gated at the call site (collections are
 * admin-managed); the book IDs list must not be empty. Loops the idempotent single-book add and
 * fails fast on the first error — mirrors AddBooksToShelfUseCase.
 */
open class AddBooksToCollectionUseCase(
    private val collectionRepository: CollectionRepository,
) {
    open suspend operator fun invoke(
        collectionId: String,
        bookIds: List<String>,
    ): AppResult<Unit> {
        if (bookIds.isEmpty()) {
            return validationError("At least one book must be selected")
        }
        logger.info { "Adding ${bookIds.size} books to collection $collectionId" }
        for (bookId in bookIds) {
            when (val result = collectionRepository.addBook(collectionId, bookId)) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> return result
            }
        }
        return AppResult.Success(Unit)
    }
}
