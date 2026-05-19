package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.CollectionShareSummary
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Shares a collection with a user.
 *
 * Creates a share that gives the specified user read access to the collection.
 *
 * Usage:
 * ```kotlin
 * val result = shareCollectionUseCase(collectionId = "col1", userId = "user1")
 * when (result) {
 *     is Success -> showSuccess(result.data)
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class ShareCollectionUseCase(
    private val collectionRepository: CollectionRepository,
) {
    /**
     * Share a collection with a user.
     *
     * @param collectionId The collection to share
     * @param userId The user to share with
     * @return Result containing the created share summary or a failure
     */
    open suspend operator fun invoke(
        collectionId: String,
        userId: String,
    ): AppResult<CollectionShareSummary> {
        logger.info { "Sharing collection $collectionId with user $userId" }

        return collectionRepository.shareCollection(collectionId, userId)
    }
}
