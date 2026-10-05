package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.client.domain.model.SeriesLineage
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
     * Observe the top-level series with every book of their subtree — the Library's Series grid.
     *
     * A series that sits inside another never appears here; its books are folded into its root's,
     * distinct and in series order ([com.calypsan.listenup.domain.series.SeriesTree.defaultBookOrder]),
     * and [SeriesWithBooks.subSeriesCount] says how many direct sub-series the root has. A root whose
     * whole subtree holds no book the library shows is left out.
     */
    fun observeRootSeriesWithBooks(): Flow<List<SeriesWithBooks>>

    /**
     * Observe the whole series hierarchy: every live series and every book the library shows in each.
     * The one place every surface reads "where does this series sit" and "how many books are under it".
     */
    fun observeHierarchy(): Flow<SeriesHierarchy>

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

    /**
     * Observe where [seriesId] sits in the series hierarchy: its ancestors, its sub-series, and —
     * when it has sub-series — the books of its whole subtree in series order.
     *
     * Emits [SeriesLineage.Flat] for a series with no parent and no sub-series.
     */
    fun observeSeriesLineage(seriesId: String): Flow<SeriesLineage>

    // ========== Search Methods ==========

    /**
     * Search series for autocomplete during book editing.
     *
     * Implements "never stranded" pattern:
     * - Online: uses the server search endpoint (ranked by book count)
     * - Offline: falls back to local Room FTS5 (simpler but always works)
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
