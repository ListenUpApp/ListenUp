package com.calypsan.listenup.client.data.remote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contributor from Audible metadata (author, narrator).
 */
@Serializable
data class MetadataContributor(
    @SerialName("asin")
    val asin: String? = null,
    @SerialName("name")
    val name: String,
)

/**
 * Series entry from Audible metadata.
 */
@Serializable
data class MetadataSeriesEntry(
    @SerialName("asin")
    val asin: String? = null,
    @SerialName("name")
    val name: String,
    @SerialName("position")
    val position: String? = null,
)

/**
 * Search result from Audible metadata search.
 * Contains basic information for display in search results list.
 */
@Serializable
data class MetadataSearchResult(
    @SerialName("asin")
    val asin: String,
    @SerialName("title")
    val title: String,
    @SerialName("subtitle")
    val subtitle: String? = null,
    @SerialName("authors")
    val authors: List<MetadataContributor> = emptyList(),
    @SerialName("narrators")
    val narrators: List<MetadataContributor> = emptyList(),
    @SerialName("series")
    val series: List<MetadataSeriesEntry> = emptyList(),
    @SerialName("cover_url")
    val coverUrl: String? = null,
    @SerialName("runtime_minutes")
    val runtimeMinutes: Int = 0,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("rating")
    val rating: Double = 0.0,
    @SerialName("rating_count")
    val ratingCount: Int = 0,
    @SerialName("language")
    val language: String? = null,
)

/**
 * Response wrapper for metadata search.
 */
@Serializable
data class MetadataSearchResponse(
    @SerialName("results")
    val results: List<MetadataSearchResult> = emptyList(),
)

/**
 * Full book details from Audible metadata.
 * Contains complete information for the preview screen.
 */
@Serializable
data class MetadataBook(
    @SerialName("asin")
    val asin: String,
    @SerialName("title")
    val title: String,
    @SerialName("subtitle")
    val subtitle: String? = null,
    @SerialName("authors")
    val authors: List<MetadataContributor> = emptyList(),
    @SerialName("narrators")
    val narrators: List<MetadataContributor> = emptyList(),
    @SerialName("series")
    val series: List<MetadataSeriesEntry> = emptyList(),
    @SerialName("genres")
    val genres: List<String> = emptyList(),
    @SerialName("description")
    val description: String? = null,
    @SerialName("publisher")
    val publisher: String? = null,
    @SerialName("cover_url")
    val coverUrl: String? = null,
    @SerialName("runtime_minutes")
    val runtimeMinutes: Int = 0,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("rating")
    val rating: Double = 0.0,
    @SerialName("rating_count")
    val ratingCount: Int = 0,
    @SerialName("language")
    val language: String? = null,
)

/**
 * Response wrapper for single book metadata fetch.
 */
@Serializable
data class MetadataBookResponse(
    @SerialName("book")
    val book: MetadataBook,
)

/**
 * Field selection flags for metadata match.
 */
@Serializable
data class MatchFields(
    @SerialName("title")
    val title: Boolean = false,
    @SerialName("subtitle")
    val subtitle: Boolean = false,
    @SerialName("description")
    val description: Boolean = false,
    @SerialName("publisher")
    val publisher: Boolean = false,
    @SerialName("releaseDate")
    val releaseDate: Boolean = false,
    @SerialName("language")
    val language: Boolean = false,
    @SerialName("cover")
    val cover: Boolean = false,
)

/**
 * Series match entry with granular control.
 */
@Serializable
data class SeriesMatchEntry(
    @SerialName("asin")
    val asin: String,
    @SerialName("applyName")
    val applyName: Boolean = true,
    @SerialName("applySequence")
    val applySequence: Boolean = true,
)

/**
 * Request body for applying metadata match to a book.
 */
@Serializable
data class ApplyMatchRequest(
    @SerialName("asin")
    val asin: String,
    @SerialName("region")
    val region: String = "us",
    @SerialName("fields")
    val fields: MatchFields = MatchFields(),
    @SerialName("authors")
    val authors: List<String> = emptyList(), // ASINs of selected authors
    @SerialName("narrators")
    val narrators: List<String> = emptyList(), // ASINs of selected narrators
    @SerialName("series")
    val series: List<SeriesMatchEntry> = emptyList(),
    @SerialName("genres")
    val genres: List<String> = emptyList(),
    @SerialName("cover_url")
    val coverUrl: String? = null, // Explicit cover URL (overrides Audible if provided)
)

// =============================================================================
// COVER SEARCH
// =============================================================================

/**
 * Cover option from multi-source cover search.
 * Represents a cover image from iTunes or Audible with its dimensions.
 */
@Serializable
data class CoverOption(
    @SerialName("source")
    val source: String, // "audible" or "itunes"
    @SerialName("url")
    val url: String,
    @SerialName("width")
    val width: Int,
    @SerialName("height")
    val height: Int,
    @SerialName("source_id")
    val sourceId: String, // ASIN or iTunes collectionId
)

/**
 * Response wrapper for cover search.
 */
@Serializable
data class CoverSearchResponse(
    @SerialName("covers")
    val covers: List<CoverOption> = emptyList(),
)

// =============================================================================
// CONTRIBUTOR METADATA
// =============================================================================

/**
 * Search result from Audible contributor search.
 * Contains basic information for display in disambiguation list.
 */
@Serializable
data class ContributorMetadataSearchResult(
    @SerialName("asin")
    val asin: String,
    @SerialName("name")
    val name: String,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("description")
    val description: String? = null, // e.g., "142 titles"
)

/**
 * Full contributor profile from Audible.
 */
@Serializable
data class ContributorMetadataProfile(
    @SerialName("asin")
    val asin: String,
    @SerialName("name")
    val name: String,
    @SerialName("biography")
    val biography: String? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
)

/**
 * Response wrapper for contributor metadata search.
 */
@Serializable
data class ContributorMetadataSearchResponse(
    @SerialName("results")
    val results: List<ContributorMetadataSearchResult> = emptyList(),
    @SerialName("region")
    val region: String = "us",
)

/**
 * Error response with candidates for 409 Conflict.
 */
@Serializable
data class ContributorMetadataConflictError(
    @SerialName("code")
    val code: String,
    @SerialName("message")
    val message: String,
    @SerialName("details")
    val details: ContributorMetadataConflictDetails? = null,
)

/**
 * Body of a [ContributorMetadataConflictError]: the [candidates] the server matched
 * for [searchedName] so the user can disambiguate.
 */
@Serializable
data class ContributorMetadataConflictDetails(
    @SerialName("candidates")
    val candidates: List<ContributorMetadataSearchResult> = emptyList(),
    @SerialName("searched_name")
    val searchedName: String? = null,
)

/**
 * Request body for applying contributor metadata.
 */
@Serializable
data class ApplyContributorMetadataRequest(
    @SerialName("asin")
    val asin: String,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("fields")
    val fields: ContributorMetadataFieldsSelection,
)

/**
 * Field selections for contributor metadata.
 */
@Serializable
data class ContributorMetadataFieldsSelection(
    @SerialName("name")
    val name: Boolean = false,
    @SerialName("biography")
    val biography: Boolean = false,
    @SerialName("image")
    val image: Boolean = false,
)
