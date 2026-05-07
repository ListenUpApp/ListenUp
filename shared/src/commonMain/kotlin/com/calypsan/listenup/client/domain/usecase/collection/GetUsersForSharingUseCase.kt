package com.calypsan.listenup.client.domain.usecase.collection

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Gets users available for sharing a collection.
 *
 * Fetches all users and filters out:
 * - Users who already have a share for this collection
 * - The current user (can't share with yourself)
 *
 * Usage:
 * ```kotlin
 * val result = getUsersForSharingUseCase(collectionId = "123")
 * when (result) {
 *     is Success -> displayUserPicker(result.data)
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class GetUsersForSharingUseCase(
    private val adminRepository: AdminRepository,
    private val collectionRepository: CollectionRepository,
    private val userRepository: UserRepository,
) {
    /**
     * Get users available for sharing a collection.
     *
     * @param collectionId The collection to get available users for
     * @return Result containing list of available users or a failure
     */
    open suspend operator fun invoke(collectionId: String): AppResult<List<AdminUserInfo>> {
        logger.debug { "Loading users available for sharing collection: $collectionId" }

        // Propagate failure if users can't be fetched — callers need the list to function.
        val allUsers =
            when (val usersResult = adminRepository.getUsers()) {
                is Success -> usersResult.data
                is Failure -> return usersResult
            }

        // Get current shares to filter out; collectionRepository.getCollectionShares()
        // returns AppResult — treat failure as empty list (non-fatal secondary call).
        val existingShares =
            when (val sharesResult = collectionRepository.getCollectionShares(collectionId)) {
                is Success -> sharesResult.data
                else -> {
                    logger.warn { "Failed to load existing shares, proceeding with all users" }
                    emptyList()
                }
            }
        val sharedUserIds = existingShares.map { it.userId }.toSet()

        // Get current user to filter out
        val currentUser = userRepository.getCurrentUser()
        val currentUserId = currentUser?.id?.value

        // Filter to available users
        val availableUsers =
            allUsers.filter { user ->
                user.id !in sharedUserIds && user.id != currentUserId
            }

        logger.debug { "Found ${availableUsers.size} users available for sharing (of ${allUsers.size} total)" }
        return AppResult.Success(availableUsers)
    }
}
