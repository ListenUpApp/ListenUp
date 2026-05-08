package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Refreshes the collection list from the server.
 *
 * Fetches the latest collections and syncs with the local database.
 * New collections are added, existing ones updated, and deleted ones removed.
 * Admin-only operation.
 *
 * Usage:
 * ```kotlin
 * val result = refreshCollectionsUseCase()
 * ```
 */
open class RefreshCollectionsUseCase(
    private val collectionRepository: CollectionRepository,
) {
    /**
     * Refresh collections from server.
     *
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(): AppResult<Unit> {
        logger.debug { "Refreshing collections from server" }

        return collectionRepository.refreshFromServer()
    }
}
