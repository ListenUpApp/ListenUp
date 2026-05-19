package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.suspendRunCatching
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.core.validationError

private val logger = KotlinLogging.logger {}

/**
 * Updates an existing shelf's name and description.
 *
 * Validates that the name is not blank before calling the repository.
 * Empty descriptions are converted to null.
 *
 * Usage:
 * ```kotlin
 * val result = updateShelfUseCase(
 *     shelfId = "shelf-123",
 *     name = "Updated Name",
 *     description = "New description"
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
     * @return Result containing the updated shelf or a failure
     */
    open suspend operator fun invoke(
        shelfId: String,
        name: String,
        description: String?,
    ): AppResult<Shelf> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return validationError("Shelf name is required")
        }

        val trimmedDescription = description?.trim()?.takeIf { it.isNotEmpty() }

        logger.info { "Updating shelf $shelfId: $trimmedName" }

        return suspendRunCatching {
            shelfRepository.updateShelf(shelfId, trimmedName, trimmedDescription)
        }
    }
}
