package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.core.ShelfId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Deletes a shelf by ID.
 *
 * Only the shelf owner can delete a shelf. The server returns a failure
 * if the user doesn't have permission.
 *
 * Usage:
 * ```kotlin
 * val result = deleteShelfUseCase(shelfId = "shelf-123")
 * ```
 */
open class DeleteShelfUseCase(
    private val shelfRepository: ShelfRepository,
) {
    /**
     * Delete a shelf.
     *
     * @param shelfId The ID of the shelf to delete
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(shelfId: ShelfId): AppResult<Unit> {
        logger.info { "Deleting shelf $shelfId" }
        return shelfRepository.deleteShelf(shelfId)
    }
}
