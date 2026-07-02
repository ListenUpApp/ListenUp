package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.result.AppResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract interface for book editing API operations.
 *
 * Handles book metadata updates and relationship management.
 */
internal interface BookApiContract {
    /**
     * Update book metadata (PATCH semantics).
     *
     * Only fields present in the request are updated:
     * - null field = don't change
     * - empty string = clear the field
     *
     * @param bookId Book to update
     * @param update Fields to update
     * @return Result containing the updated book
     */
    suspend fun updateBook(
        bookId: String,
        update: BookUpdateRequest,
    ): AppResult<BookEditResponse>

    /**
     * Set book contributors (replaces all existing contributors).
     *
     * Contributors are matched by name:
     * - Existing contributor with same name → linked
     * - New name → contributor created automatically
     *
     * Orphaned contributors (no books) are automatically cleaned up.
     *
     * @param bookId Book to update
     * @param contributors New list of contributors with roles
     * @return Result containing the updated book
     */
    suspend fun setBookContributors(
        bookId: String,
        contributors: List<ContributorInput>,
    ): AppResult<BookEditResponse>

    /**
     * Set book series (replaces all existing series relationships).
     *
     * Series are matched by name:
     * - Existing series with same name → linked
     * - New name → series created automatically
     *
     * Orphaned series (no books) are automatically cleaned up.
     *
     * @param bookId Book to update
     * @param series New list of series with sequence numbers
     * @return Result containing the updated book
     */
    suspend fun setBookSeries(
        bookId: String,
        series: List<SeriesInput>,
    ): AppResult<BookEditResponse>
}

/**
 * Request for updating book metadata (PATCH semantics).
 *
 * Only non-null fields are sent to the server:
 * - null = don't change this field
 * - empty string = clear this field
 */
@Serializable
internal data class BookUpdateRequest(
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    @SerialName("publish_year")
    val publishYear: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val asin: String? = null,
    val abridged: Boolean? = null,
    @SerialName("series_id")
    val seriesId: String? = null,
    val sequence: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null, // ISO8601 timestamp for when book was added to library
)

/**
 * Contributor with roles for setting book contributors.
 */
internal data class ContributorInput(
    val name: String,
    val roles: List<String>,
)

/**
 * Series with sequence for setting book series.
 */
internal data class SeriesInput(
    val name: String,
    val sequence: String?,
)

/**
 * Book response for edit operations.
 *
 * Contains fields needed after editing. Separate from SyncModels.BookResponse
 * which has additional sync-specific fields (chapters, audio files, etc.).
 */
internal data class BookEditResponse(
    val id: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val publisher: String?,
    val publishYear: String?,
    val language: String?,
    val isbn: String?,
    val asin: String?,
    val abridged: Boolean,
    val seriesId: String?,
    val seriesName: String?,
    val sequence: String?,
    val updatedAt: String,
)
