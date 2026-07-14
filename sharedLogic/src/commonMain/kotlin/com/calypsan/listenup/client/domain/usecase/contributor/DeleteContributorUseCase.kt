package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.core.ContributorId
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Deletes a contributor by ID.
 *
 * Delegates to the RPC-backed [ContributorEditRepository] — mutation belongs on the
 * edit repo per the established observe/edit split. The server hard-deletes the
 * junction rows, soft-deletes the contributor, and the authoritative state flows
 * back via SSE (no optimistic Room write here).
 *
 * Usage:
 * ```kotlin
 * val result = deleteContributorUseCase(contributorId = "contributor-123")
 * when (result) {
 *     is AppResult.Success -> navigateBack()
 *     is AppResult.Failure -> showError(result.message)
 * }
 * ```
 */
open class DeleteContributorUseCase(
    private val contributorEditRepository: ContributorEditRepository,
) {
    /**
     * Delete a contributor.
     *
     * @param contributorId The ID of the contributor to delete
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(contributorId: String): AppResult<Unit> {
        logger.info { "Deleting contributor $contributorId" }
        return contributorEditRepository.deleteContributor(ContributorId(contributorId))
    }
}
