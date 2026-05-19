package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Removes a share (unshares a collection from a user).
 *
 * Deletes the share, revoking the user's access to the collection.
 *
 * Usage:
 * ```kotlin
 * val result = removeCollectionShareUseCase(shareId = "share123")
 * when (result) {
 *     is Success -> showSuccess()
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class RemoveCollectionShareUseCase(
    private val collectionRepository: CollectionRepository,
) {
    /**
     * Remove a collection share.
     *
     * @param shareId The share to remove
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(shareId: String): AppResult<Unit> {
        logger.info { "Removing share: $shareId" }

        return collectionRepository.removeShare(shareId)
    }
}
