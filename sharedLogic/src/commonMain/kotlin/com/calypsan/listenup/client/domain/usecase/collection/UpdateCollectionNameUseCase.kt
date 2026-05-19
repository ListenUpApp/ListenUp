package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.validationError
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Updates a collection's name.
 *
 * Validates that the name is not blank, then updates on server.
 * SSE events will sync the change to local database.
 *
 * Usage:
 * ```kotlin
 * val result = updateCollectionNameUseCase(collectionId = "123", name = "New Name")
 * when (result) {
 *     is Success -> showSuccess(result.data)
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class UpdateCollectionNameUseCase(
    private val collectionRepository: CollectionRepository,
) {
    /**
     * Update a collection's name.
     *
     * @param collectionId The collection to update
     * @param name The new name (required, will be trimmed)
     * @return Result containing the updated collection or a failure
     */
    open suspend operator fun invoke(
        collectionId: String,
        name: String,
    ): AppResult<Collection> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return validationError("Collection name cannot be empty")
        }

        logger.info { "Updating collection $collectionId name to: $trimmedName" }

        return collectionRepository.updateCollectionName(collectionId, trimmedName)
    }
}
