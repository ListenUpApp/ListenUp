package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult

/**
 * Repository contract for book editing operations.
 *
 * Provides methods for modifying book metadata and contributors.
 * Uses offline-first pattern: changes are applied locally immediately
 * and queued for sync to server.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface BookEditRepository {
    /**
     * Update book metadata.
     *
     * Applies update locally and queues for server sync.
     * Only non-null fields in the request are updated (PATCH semantics).
     *
     * @param bookId ID of the book to update
     * @param title New title (null = don't change)
     * @param subtitle New subtitle (null = don't change)
     * @param description New description (null = don't change)
     * @param publisher New publisher (null = don't change)
     * @param publishYear New publish year (null = don't change)
     * @param language New language code (null = don't change)
     * @param isbn New ISBN (null = don't change)
     * @param asin New ASIN (null = don't change)
     * @param abridged New abridged status (null = don't change)
     * @return Result indicating success or failure
     */
    suspend fun updateBook(
        bookId: String,
        title: String? = null,
        sortTitle: String? = null,
        subtitle: String? = null,
        description: String? = null,
        publisher: String? = null,
        publishYear: String? = null,
        language: String? = null,
        isbn: String? = null,
        asin: String? = null,
        abridged: Boolean? = null,
    ): AppResult<Unit>

    /**
     * Set book contributors (replaces all existing contributors).
     *
     * Queues operation for server sync. On sync, contributors are matched
     * by name - existing contributors are linked, new names create new contributors.
     *
     * @param bookId ID of the book to update
     * @param contributors New list of contributors with roles
     * @return Result indicating success or failure
     */
    suspend fun setBookContributors(
        bookId: String,
        contributors: List<BookContributorInput>,
    ): AppResult<Unit>

    /**
     * Set book series (replaces all existing series relationships).
     *
     * Queues operation for server sync. On sync, series are matched
     * by name - existing series are linked, new names create new series.
     *
     * @param bookId ID of the book to update
     * @param series New list of series with sequence numbers
     * @return Result indicating success or failure
     */
    suspend fun setBookSeries(
        bookId: String,
        series: List<BookSeriesInput>,
    ): AppResult<Unit>
}

/**
 * Input for setting a book's contributor.
 */
data class BookContributorInput(
    val name: String,
    val roles: List<String>,
)

/**
 * Input for setting a book's series membership.
 */
data class BookSeriesInput(
    val name: String,
    val sequence: Float?,
)
