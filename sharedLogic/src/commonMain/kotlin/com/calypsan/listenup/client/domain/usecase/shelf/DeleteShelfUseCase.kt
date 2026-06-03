package com.calypsan.listenup.client.domain.usecase.shelf

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Deletes a shelf by ID.
 *
 * Only the shelf owner can delete a shelf. The server will return an error
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
    open suspend operator fun invoke(shelfId: String): AppResult<Unit> {
        logger.info { "Deleting shelf $shelfId" }

        return suspendRunCatching {
            shelfRepository.deleteShelf(shelfId)
        }
    }
}
