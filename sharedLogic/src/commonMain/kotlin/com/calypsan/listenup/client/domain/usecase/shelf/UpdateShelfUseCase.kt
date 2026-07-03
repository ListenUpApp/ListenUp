package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.validationError
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.core.ShelfId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Updates an existing shelf's name, description, and privacy flag.
 *
 * Validates that the name is not blank before calling the repository.
 * Empty descriptions are converted to null.
 *
 * Usage:
 * ```kotlin
 * val result = updateShelfUseCase(
 *     shelfId = "shelf-123",
 *     name = "Updated Name",
 *     description = "New description",
 *     isPrivate = true,
 * )
 * ```
 */
open class UpdateShelfUseCase(
    private val shelfRepository: ShelfRepository,
) {
    /**
     * Update an existing shelf.
     *
     * @param shelfId The ID of the shelf to update
     * @param name The new shelf name (required, will be trimmed)
     * @param description Optional new description (empty strings converted to null)
     * @param isPrivate The new privacy flag
     * @return Result containing the updated shelf or a failure
     */
    open suspend operator fun invoke(
        shelfId: ShelfId,
        name: String,
        description: String?,
        isPrivate: Boolean = false,
    ): AppResult<Shelf> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return validationError("Shelf name is required")
        }

        val trimmedDescription = description?.trim()?.takeIf { it.isNotEmpty() }

        logger.info { "Updating shelf $shelfId: $trimmedName" }

        return shelfRepository.updateShelf(shelfId, trimmedName, trimmedDescription, isPrivate)
    }
}
