package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.validationError
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Creates a new collection with the given name.
 *
 * Validates that the name is not blank, then delegates to the repository
 * which creates on server and persists locally for immediate UI feedback.
 *
 * Usage:
 * ```kotlin
 * val result = createCollectionUseCase(name = "My Reading List")
 * when (result) {
 *     is Success -> showSuccess(result.data)
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class CreateCollectionUseCase(
    private val collectionRepository: CollectionRepository,
) {
    /**
     * Create a new collection.
     *
     * @param name The collection name (required, will be trimmed)
     * @return Result containing the created collection or a failure
     */
    open suspend operator fun invoke(name: String): AppResult<Collection> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return validationError("Collection name is required")
        }

        logger.info { "Creating collection: $trimmedName" }

        return collectionRepository.create(trimmedName)
    }
}
