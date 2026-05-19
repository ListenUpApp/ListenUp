package com.calypsan.listenup.client.domain.model

/**
 * Lightweight contributor representation for search autocomplete.
 *
 * Used when editing book contributors to find existing contributors to link.
 * Contains only the minimum information needed for display and selection.
 */
data class ContributorSearchResult(
    val id: String,
    val name: String,
    val bookCount: Int,
)

/**
 * Response from contributor search operations.
 *
 * Contains the search results along with metadata about the search source.
 * The `isOfflineResult` flag indicates if results came from local FTS
 * (offline fallback) rather than the server.
 */
data class ContributorSearchResponse(
    val contributors: List<ContributorSearchResult>,
    val isOfflineResult: Boolean,
    val tookMs: Long,
)

/**
 * Result of applying contributor metadata from external sources (e.g., Audible).
 */
sealed interface ContributorMetadataResult {
    /**
     * Metadata applied successfully.
     *
     * @property contributor The updated contributor with applied metadata (null for simple success)
     */
    data class Success(
        val contributor: ContributorWithMetadata? = null,
    ) : ContributorMetadataResult

    /**
     * Disambiguation required - either multiple matches found or no matches found.
     * If candidates is empty, the user should be prompted to search with a different name.
     *
     * @param options List of matching contributors from the external source (may be empty)
     */
    data class NeedsDisambiguation(
        val options: List<ContributorMetadataCandidate>,
    ) : ContributorMetadataResult

    /** Error occurred */
    data class Error(
        val message: String,
    ) : ContributorMetadataResult
}

/**
 * Contributor data returned from metadata application.
 */
data class ContributorWithMetadata(
    val id: String,
    val name: String,
    val biography: String?,
    val imageUrl: String?,
    val imageBlurHash: String?,
)

/**
 * A candidate contributor from external metadata search (e.g., Audible).
 *
 * Used when disambiguation is needed to show the user possible matches.
 */
data class ContributorMetadataCandidate(
    val asin: String,
    val name: String,
    val imageUrl: String?,
    /** Description text, e.g., "142 titles" */
    val description: String?,
)
