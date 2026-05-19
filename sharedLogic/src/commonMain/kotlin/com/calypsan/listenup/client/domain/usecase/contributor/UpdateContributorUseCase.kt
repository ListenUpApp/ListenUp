package com.calypsan.listenup.client.domain.usecase.contributor

import com.calypsan.listenup.core.Failure
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.suspendRunCatching
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Updates a contributor with optional alias merging.
 *
 * This use case orchestrates:
 * 1. Merging existing contributors when added as aliases
 * 2. Updating contributor metadata (name, biography, etc.)
 *
 * Alias Merge Flow:
 * When "Richard Bachman" is added as an alias to Stephen King:
 * - If a ContributorEntity named "Richard Bachman" exists, it gets merged
 * - The merge re-links all book relationships from source to target
 * - The source contributor is deleted locally
 * - All operations are queued for server sync
 *
 * Usage:
 * ```kotlin
 * val result = updateContributorUseCase(
 *     request = ContributorUpdateRequest(
 *         contributorId = "contributor-123",
 *         name = "Stephen King",
 *         biography = "...",
 *         aliases = listOf("Richard Bachman"),
 *         newAliases = setOf("Richard Bachman"),
 *         contributorsToMerge = mapOf("richard bachman" to searchResult),
 *     )
 * )
 * ```
 */
open class UpdateContributorUseCase(
    private val contributorEditRepository: ContributorEditRepository,
) {
    /**
     * Update contributor with optional alias merging.
     *
     * @param request The update request containing all changes
     * @return Result indicating success or failure
     */
    open suspend operator fun invoke(request: ContributorUpdateRequest): AppResult<Unit> =
        suspendRunCatching {
            logger.info { "Updating contributor ${request.contributorId}" }

            // 1. Handle new aliases - merge contributors
            for (newAlias in request.newAliases) {
                val tracked = request.contributorsToMerge[newAlias.lowercase()]
                if (tracked != null && tracked.id != request.contributorId) {
                    // This alias corresponds to an existing contributor - merge it
                    when (
                        val result =
                            contributorEditRepository.mergeContributor(
                                targetId = request.contributorId,
                                sourceId = tracked.id,
                            )
                    ) {
                        is Success -> {
                            logger.info { "Merged contributor ${tracked.id} into ${request.contributorId}" }
                        }

                        is Failure -> {
                            logger.warn { "Merge failed for ${tracked.id}: ${result.message}" }
                            // Continue - alias will still be added via update
                        }
                    }
                }
            }

            // 2. Update contributor metadata
            when (
                val result =
                    contributorEditRepository.updateContributor(
                        contributorId = request.contributorId,
                        name = request.name,
                        biography = request.biography?.ifBlank { null },
                        website = request.website?.ifBlank { null },
                        birthDate = request.birthDate?.ifBlank { null },
                        deathDate = request.deathDate?.ifBlank { null },
                        aliases = request.aliases,
                    )
            ) {
                is Success -> {
                    logger.info { "Contributor update queued: ${request.name}" }
                }

                is Failure -> {
                    throw null as Exception? ?: Exception(result.message)
                }
            }
        }
}

/**
 * Request data for updating a contributor.
 */
data class ContributorUpdateRequest(
    val contributorId: String,
    val name: String,
    val biography: String? = null,
    val website: String? = null,
    val birthDate: String? = null,
    val deathDate: String? = null,
    val aliases: List<String> = emptyList(),
    /**
     * Set of aliases that are new (not in the original list).
     * These may trigger contributor merges.
     */
    val newAliases: Set<String> = emptySet(),
    /**
     * Map of alias name (lowercase) to the contributor search result.
     * Used to find existing contributors to merge.
     */
    val contributorsToMerge: Map<String, ContributorSearchResult> = emptyMap(),
)
