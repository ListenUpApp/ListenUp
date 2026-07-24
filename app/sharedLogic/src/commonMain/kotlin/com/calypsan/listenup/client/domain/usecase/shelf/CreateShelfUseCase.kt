package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.validationError
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Creates a new shelf with the given name, optional description, and privacy flag.
 *
 * Validates that the name is not blank before calling the repository.
 * Empty descriptions are converted to null.
 *
 * Usage:
 * ```kotlin
 * val result = createShelfUseCase(
 *     name = "My Reading List",
 *     description = "Books I want to read",
 *     isPrivate = false,
 * )
 * ```
 */
open class CreateShelfUseCase(
    private val shelfRepository: ShelfRepository,
) {
    /**
     * Create a new shelf.
     *
     * @param name The shelf name (required, will be trimmed)
     * @param description Optional description (empty strings converted to null)
     * @param isPrivate Whether the shelf is visible only to the owner
     * @return Result containing the created shelf or a failure
     */
    open suspend operator fun invoke(
        name: String,
        description: String?,
        isPrivate: Boolean = false,
    ): AppResult<Shelf> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return validationError("Shelf name is required")
        }

        val trimmedDescription = description?.trim()?.takeIf { it.isNotEmpty() }

        logger.info { "Creating shelf: $trimmedName" }

        return shelfRepository.createShelf(trimmedName, trimmedDescription, isPrivate)
    }
}
