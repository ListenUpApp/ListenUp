package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult

/**
 * Repository contract for contributor editing operations.
 *
 * Provides methods for modifying contributor metadata and managing aliases.
 * Changes are applied locally immediately; server propagation for contributor
 * edits is a Books-C concern and is not yet wired.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface ContributorEditRepository {
    /**
     * Update contributor metadata.
     *
     * Applies update locally. Only non-null fields are updated (PATCH semantics).
     *
     * @param contributorId ID of the contributor to update
     * @param name New name (null = don't change)
     * @param biography New biography (null = don't change)
     * @param website New website URL (null = don't change)
     * @param birthDate New birth date (null = don't change)
     * @param deathDate New death date (null = don't change)
     * @param aliases New list of aliases (null = don't change)
     * @return Result indicating success or failure
     */
    suspend fun updateContributor(
        contributorId: String,
        name: String? = null,
        biography: String? = null,
        website: String? = null,
        birthDate: String? = null,
        deathDate: String? = null,
        aliases: List<String>? = null,
    ): AppResult<Unit>

    /**
     * Merge a source contributor into a target contributor.
     *
     * Local effects (applied immediately):
     * - Re-links all book relationships from source to target (with creditedAs)
     * - Adds source name to target's aliases
     * - Deletes source contributor
     *
     * @param targetId ID of the target contributor (receives the merge)
     * @param sourceId ID of the source contributor (will be deleted)
     * @return Result indicating success or failure
     */
    suspend fun mergeContributor(
        targetId: String,
        sourceId: String,
    ): AppResult<Unit>

    /**
     * Unmerge an alias from a contributor, creating a new contributor.
     *
     * Local effects (applied immediately):
     * - Creates new contributor with the alias name (temporary local ID)
     * - Re-links book relationships where creditedAs matches the alias
     * - Removes alias from original contributor
     *
     * @param contributorId ID of the contributor to unmerge from
     * @param aliasName Name of the alias to split out
     * @return Result indicating success or failure
     */
    suspend fun unmergeContributor(
        contributorId: String,
        aliasName: String,
    ): AppResult<Unit>
}
