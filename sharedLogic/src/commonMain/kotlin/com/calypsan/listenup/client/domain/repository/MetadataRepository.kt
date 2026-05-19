package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.ContributorMetadataCandidate
import com.calypsan.listenup.client.domain.model.ContributorMetadataResult

/**
 * Repository contract for metadata lookup operations.
 *
 * Provides Audible metadata search and book matching functionality.
 * Used by book edit screens for metadata enrichment.
 *
 * Part of the domain layer - implementations live in the data layer.
 * Note: Uses data layer types for metadata results since these are
 * tightly coupled to the API responses.
 */
interface MetadataRepository {
    /**
     * Search Audible for matching audiobooks.
     *
     * @param query Search query (title, author, etc.)
     * @param region Audible region code (default: "us")
     * @return List of matching results
     */
    suspend fun searchAudible(
        query: String,
        region: String = "us",
    ): List<MetadataSearchResult>

    /**
     * Get full metadata for a specific Audible book.
     *
     * @param asin Audible Standard Identification Number
     * @param region Audible region code (default: "us")
     * @return Full book metadata
     */
    suspend fun getMetadataPreview(
        asin: String,
        region: String = "us",
    ): MetadataBook

    /**
     * Apply Audible metadata match to a book.
     *
     * Downloads cover art and updates book metadata from the matched
     * Audible entry. After calling this, the caller should trigger
     * a sync to get the updated book data.
     *
     * @param bookId Local book ID to update
     * @param request Match request with field selections
     */
    suspend fun applyMatch(
        bookId: String,
        request: ApplyMatchRequest,
    )

    /**
     * Search for cover images from multiple sources (iTunes, Audible).
     *
     * @param title Book title to search for
     * @param author Author name (optional, improves results)
     * @return List of cover options sorted by resolution (highest first)
     */
    suspend fun searchCovers(
        title: String,
        author: String,
    ): List<CoverOption>

    /**
     * Apply Audible metadata to a contributor.
     *
     * @param contributorId Local contributor ID to update
     * @param asin Audible ASIN for the contributor
     * @param imageUrl Optional image URL to download
     * @param applyName Whether to update the contributor name
     * @param applyBiography Whether to update the biography
     * @param applyImage Whether to update the image
     * @return Result containing success, disambiguation options, or error
     */
    suspend fun applyContributorMetadata(
        contributorId: String,
        asin: String,
        imageUrl: String?,
        applyName: Boolean,
        applyBiography: Boolean,
        applyImage: Boolean,
    ): ContributorMetadataResult

    /**
     * Search Audible for matching contributors.
     *
     * @param query Search query (contributor name)
     * @param region Audible region code (e.g., "us", "uk", "de")
     * @return List of matching contributor candidates
     */
    suspend fun searchContributors(
        query: String,
        region: String = "us",
    ): List<ContributorMetadataCandidate>

    /**
     * Get full contributor profile from Audible.
     *
     * @param asin Audible Standard Identification Number
     * @return Full contributor profile with biography
     */
    suspend fun getContributorProfile(asin: String): ContributorMetadataProfile
}

/**
 * Full contributor profile from Audible.
 *
 * Contains complete metadata that can be applied to a local contributor.
 */
data class ContributorMetadataProfile(
    val asin: String,
    val name: String,
    val biography: String? = null,
    val imageUrl: String? = null,
)

// Domain types for metadata operations
// These mirror the API types but live in the domain layer

/**
 * Result from Audible metadata search.
 */
data class MetadataSearchResult(
    val asin: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val releaseDate: String? = null,
    val runtimeMinutes: Int? = null,
    val coverUrl: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val rating: Double? = null,
    val ratingCount: Int = 0,
)

/**
 * Full metadata for an Audible book.
 */
data class MetadataBook(
    val asin: String,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val authors: List<MetadataContributor> = emptyList(),
    val narrators: List<MetadataContributor> = emptyList(),
    val releaseDate: String? = null,
    val runtimeMinutes: Int = 0,
    val coverUrl: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val series: List<MetadataSeriesEntry> = emptyList(),
    val genres: List<String> = emptyList(),
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
)

/**
 * Contributor from metadata (author, narrator).
 */
data class MetadataContributor(
    val asin: String? = null,
    val name: String,
)

/**
 * Series entry from metadata.
 */
data class MetadataSeriesEntry(
    val asin: String? = null,
    val name: String,
    val position: String? = null,
)

/**
 * Request to apply matched metadata to a book.
 */
data class ApplyMatchRequest(
    val asin: String,
    val region: String = "us",
    val fields: MatchFields = MatchFields(),
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<SeriesMatchEntry> = emptyList(),
    val genres: List<String> = emptyList(),
    val coverUrl: String? = null,
)

/**
 * Field selection flags for metadata matching.
 */
data class MatchFields(
    val title: Boolean = true,
    val subtitle: Boolean = true,
    val description: Boolean = true,
    val publisher: Boolean = true,
    val releaseDate: Boolean = true,
    val language: Boolean = true,
    val cover: Boolean = true,
)

/**
 * Series entry for metadata matching.
 */
data class SeriesMatchEntry(
    val asin: String,
    val applyName: Boolean = true,
    val applySequence: Boolean = true,
)

/**
 * A cover image option from search.
 */
data class CoverOption(
    val url: String,
    val source: String,
    val width: Int?,
    val height: Int?,
)
