package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.repository.ContributorRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Deletes a contributor by ID.
 *
 * Delegates to the contributor repository for the actual deletion.
 * The server handles cascade operations (removing from books, etc.).
 *
 * Usage:
 * ```kotlin
 * val result = deleteContributorUseCase(contributorId = "contributor-123")
 * when (result) {
 *     is Success -> navigateBack()
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class DeleteContributorUseCase(
    private val contributorRepository: ContributorRepository,
) {
    /**
     * Delete a contributor.
     *
     * @param contributorId The ID of the contributor to delete
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(contributorId: String): AppResult<Unit> {
        logger.info { "Deleting contributor $contributorId" }
        return contributorRepository.deleteContributor(contributorId)
    }
}
