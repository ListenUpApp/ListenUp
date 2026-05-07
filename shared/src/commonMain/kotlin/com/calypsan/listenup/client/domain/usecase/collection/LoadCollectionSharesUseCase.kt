package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.mapSuspend
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.CollectionShareSummary
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Loads shares for a collection with enriched user information.
 *
 * Fetches shares from the collection repository and enriches them
 * with user details (name, email) from the admin repository.
 *
 * Usage:
 * ```kotlin
 * val result = loadCollectionSharesUseCase(collectionId = "123")
 * when (result) {
 *     is Success -> displayShares(result.data)
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class LoadCollectionSharesUseCase(
    private val collectionRepository: CollectionRepository,
    private val adminRepository: AdminRepository,
) {
    /**
     * Load shares for a collection with user information.
     *
     * @param collectionId The collection ID
     * @return Result containing list of enriched share summaries or a failure
     */
    open suspend operator fun invoke(collectionId: String): AppResult<List<CollectionShareSummary>> {
        logger.debug { "Loading shares for collection: $collectionId" }

        return collectionRepository.getCollectionShares(collectionId).mapSuspend { shares ->
            // Get users to enrich with names/emails; treat failure as empty list (non-fatal).
            val users =
                when (val usersResult = adminRepository.getUsers()) {
                    is Success -> {
                        usersResult.data
                    }

                    is Failure -> {
                        logger.warn { "Failed to load users for share enrichment, using partial data" }
                        emptyList()
                    }
                }
            val userMap = users.associateBy { it.id }

            // Enrich shares with user information
            val enrichedShares =
                shares.map { share ->
                    val user = userMap[share.userId]
                    share.withUserInfo(user)
                }

            logger.debug { "Loaded ${enrichedShares.size} shares for collection $collectionId" }
            enrichedShares
        }
    }

    /**
     * Enrich a share with user information.
     *
     * Uses display name > first+last name > email > truncated ID as fallback chain.
     */
    private fun CollectionShareSummary.withUserInfo(user: AdminUserInfo?): CollectionShareSummary {
        val userName =
            user?.let {
                it.displayName?.takeIf { name -> name.isNotBlank() }
                    ?: "${it.firstName ?: ""} ${it.lastName ?: ""}".trim().takeIf { name -> name.isNotBlank() }
                    ?: it.email
            } ?: "User ${userId.take(8)}..."

        return copy(
            userName = userName,
            userEmail = user?.email ?: "",
        )
    }
}
