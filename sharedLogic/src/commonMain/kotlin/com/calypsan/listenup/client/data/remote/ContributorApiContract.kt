package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult

/**
 * Contract interface for contributor API operations.
 *
 * Handles contributor search, updates, and merge/unmerge operations.
 */
internal interface ContributorApiContract {
    /**
     * Search contributors for autocomplete during book editing.
     *
     * Uses server-side Bleve search for O(log n) performance with:
     * - Prefix matching ("bran" → "Brandon Sanderson")
     * - Word matching ("sanderson" in "Brandon Sanderson")
     * - Fuzzy matching for typo tolerance
     *
     * @param query Search query (min 2 characters recommended)
     * @param limit Maximum results to return (default 10, max 50)
     * @return Result containing list of matching contributors
     */
    suspend fun searchContributors(
        query: String,
        limit: Int = 10,
    ): AppResult<List<ContributorSearchResult>>

    /**
     * Update a contributor's metadata.
     *
     * PUT /api/v1/contributors/{contributorId}
     *
     * Updates name, biography, website, birth_date, death_date, and aliases.
     *
     * @param contributorId The contributor to update
     * @param request The update request containing new field values
     * @return Result containing the updated contributor
     */
    suspend fun updateContributor(
        contributorId: String,
        request: UpdateContributorRequest,
    ): AppResult<UpdateContributorResponse>

    /**
     * Delete a contributor.
     *
     * DELETE /api/v1/contributors/{contributorId}
     *
     * Soft-deletes the contributor. Books associated with this contributor
     * will have their contributor links removed.
     *
     * @param contributorId The contributor to delete
     * @return Result indicating success or failure
     */
    suspend fun deleteContributor(contributorId: String): AppResult<Unit>
}

/**
 * Contributor search result for autocomplete.
 *
 * Lightweight representation returned by contributor search endpoint.
 * Used when editing book contributors to find existing contributors to link.
 */
internal data class ContributorSearchResult(
    val id: String,
    val name: String,
    val bookCount: Int,
)

/**
 * Request to update a contributor's metadata.
 */
internal data class UpdateContributorRequest(
    val name: String,
    val biography: String?,
    val website: String?,
    val birthDate: String?,
    val deathDate: String?,
    val aliases: List<String>,
)

/**
 * Response from updating a contributor.
 */
internal data class UpdateContributorResponse(
    val id: String,
    val name: String,
    val biography: String?,
    val imageUrl: String?,
    val website: String?,
    val birthDate: String?,
    val deathDate: String?,
    val aliases: List<String>,
    val updatedAt: String,
)
