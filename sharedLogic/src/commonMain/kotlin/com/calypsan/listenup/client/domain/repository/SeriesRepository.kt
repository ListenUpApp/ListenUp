package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesSearchResponse
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for series operations.
 *
 * Provides access to series information with offline-first patterns.
 * All Flow-returning methods observe the local database for reactive updates.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface SeriesRepository {
    /**
     * Observe all series reactively, sorted by name.
     *
     * @return Flow emitting list of all series
     */
    fun observeAll(): Flow<List<Series>>

    /**
     * Observe a specific series by ID reactively.
     *
     * @param id The series ID
     * @return Flow emitting the series or null if not found
     */
    fun observeById(id: String): Flow<Series?>

    /**
     * Get a series by ID synchronously.
     *
     * @param id The series ID
     * @return Series if found, null otherwise
     */
    suspend fun getById(id: String): Series?

    /**
     * Observe the series for a specific book reactively.
     *
     * A book can belong to multiple series, but this returns the first/primary series.
     * For all series of a book, use BookRepository methods.
     *
     * @param bookId The book ID
     * @return Flow emitting the series or null if book has no series
     */
    fun observeByBookId(bookId: String): Flow<Series?>

    /**
     * Get all book IDs that belong to a specific series.
     *
     * @param seriesId The series ID
     * @return List of book IDs in this series
     */
    suspend fun getBookIdsForSeries(seriesId: String): List<String>

    /**
     * Observe all book IDs that belong to a specific series reactively.
     *
     * @param seriesId The series ID
     * @return Flow emitting list of book IDs in this series
     */
    fun observeBookIdsForSeries(seriesId: String): Flow<List<String>>

    // ========== Library View Methods ==========

    /**
     * Observe all series with their books.
     *
     * Used for displaying series list in library views where book covers are needed
     * for cover stacks.
     *
     * @return Flow emitting list of series with all book data
     */
    fun observeAllWithBooks(): Flow<List<SeriesWithBooks>>

    // ========== Series Detail Methods ==========

    /**
     * Observe a specific series with all its books.
     *
     * Used for series detail page.
     *
     * @param seriesId The series ID
     * @return Flow emitting series with books, or null if not found
     */
    fun observeSeriesWithBooks(seriesId: String): Flow<SeriesWithBooks?>

    // ========== Search Methods ==========

    /**
     * Search series for autocomplete during book editing.
     *
     * Implements "never stranded" pattern:
     * - Online: Uses server Bleve search (fuzzy, ranked by book count)
     * - Offline: Falls back to local Room FTS5 (simpler but always works)
     *
     * @param query Search query (min 2 characters recommended)
     * @param limit Maximum results to return (default 10)
     * @return Search response with matching series
     */
    suspend fun searchSeries(
        query: String,
        limit: Int = 10,
    ): SeriesSearchResponse
}
