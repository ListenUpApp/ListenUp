package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.SkipQueryVerification

/**
 * Result class for book search that includes denormalized author name.
 */
data class BookSearchResult(
    @Embedded val book: BookEntity,
    val authorName: String?,
)

/**
 * DAO for local full-text search using FTS5.
 *
 * Provides offline search capability as fallback when server is unavailable.
 * Uses FTS5 MATCH queries with bm25() ranking by relevance.
 *
 * FTS5 tables are created via FtsTableCallback in platform DatabaseModules (not Room entities)
 * because Room KMP doesn't support @Fts5 annotation. We use @SkipQueryVerification
 * to bypass compile-time validation of FTS queries since Room can't see the
 * FTS virtual tables at compile time.
 *
 * Tables:
 * - books_fts: bookId, title, subtitle, description, author, narrator, seriesName, genres
 * - contributors_fts: contributorId, name, description
 * - series_fts: seriesId, name, description
 */
@Dao
interface SearchDao {
    // ==================== SEARCH QUERIES ====================

    /**
     * Search books using FTS5.
     *
     * Returns books matching the query, ranked by relevance using bm25().
     * The query should use FTS5 syntax with prefix matching (e.g., "brandon*").
     * Includes the denormalized author name from the FTS table.
     *
     * Note: We join on the bookId column stored in the FTS table, not rowid,
     * since FTS5 manages its own internal rowids.
     *
     * @param query FTS5 query string (should include * for prefix matching)
     * @param limit Max results to return
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT b.*, fts.author AS authorName
        FROM books_fts fts
        INNER JOIN books b ON fts.bookId = b.id
        WHERE books_fts MATCH :query
        ORDER BY bm25(books_fts)
        LIMIT :limit
    """,
    )
    suspend fun searchBooks(
        query: String,
        limit: Int = 20,
    ): List<BookSearchResult>

    /**
     * Search contributors using FTS5.
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT c.*
        FROM contributors_fts fts
        INNER JOIN contributors c ON fts.contributorId = c.id
        WHERE contributors_fts MATCH :query
        ORDER BY bm25(contributors_fts)
        LIMIT :limit
    """,
    )
    suspend fun searchContributors(
        query: String,
        limit: Int = 10,
    ): List<ContributorEntity>

    /**
     * Search series using FTS5.
     */
    @SkipQueryVerification
    @Query(
        """
        SELECT s.*
        FROM series_fts fts
        INNER JOIN series s ON fts.seriesId = s.id
        WHERE series_fts MATCH :query
        ORDER BY bm25(series_fts)
        LIMIT :limit
    """,
    )
    suspend fun searchSeries(
        query: String,
        limit: Int = 10,
    ): List<SeriesEntity>

    /**
     * Search tags by slug.
     *
     * Uses simple LIKE query since tags are just slugs (e.g., "slow-burn").
     * Searches for slugs containing the query, ordered by book count.
     *
     * @param query Search pattern (will be wrapped in % for LIKE)
     * @param limit Max results to return
     */
    @Query(
        """
        SELECT * FROM tags
        WHERE slug LIKE '%' || :query || '%'
        ORDER BY bookCount DESC, slug ASC
        LIMIT :limit
    """,
    )
    suspend fun searchTags(
        query: String,
        limit: Int = 10,
    ): List<TagEntity>

    // ==================== FTS POPULATION ====================

    /**
     * Clear all book FTS entries.
     */
    @SkipQueryVerification
    @Query("DELETE FROM books_fts")
    suspend fun clearBooksFts()

    /**
     * Clear all contributor FTS entries.
     */
    @SkipQueryVerification
    @Query("DELETE FROM contributors_fts")
    suspend fun clearContributorsFts()

    /**
     * Clear all series FTS entries.
     */
    @SkipQueryVerification
    @Query("DELETE FROM series_fts")
    suspend fun clearSeriesFts()

    /**
     * Insert a book FTS entry.
     *
     * Uses raw SQL since Room doesn't support INSERT into FTS5 tables directly.
     */
    @SkipQueryVerification
    @Query(
        """
        INSERT INTO books_fts (bookId, title, subtitle, description, author, narrator, seriesName, genres)
        VALUES (:bookId, :title, :subtitle, :description, :author, :narrator, :seriesName, :genres)
    """,
    )
    suspend fun insertBookFts(
        bookId: String,
        title: String,
        subtitle: String?,
        description: String?,
        author: String?,
        narrator: String?,
        seriesName: String?,
        genres: String?,
    )

    /**
     * Insert a contributor FTS entry.
     */
    @SkipQueryVerification
    @Query(
        """
        INSERT INTO contributors_fts (contributorId, name, description)
        VALUES (:contributorId, :name, :description)
    """,
    )
    suspend fun insertContributorFts(
        contributorId: String,
        name: String,
        description: String?,
    )

    /**
     * Insert a series FTS entry.
     */
    @SkipQueryVerification
    @Query(
        """
        INSERT INTO series_fts (seriesId, name, description)
        VALUES (:seriesId, :name, :description)
    """,
    )
    suspend fun insertSeriesFts(
        seriesId: String,
        name: String,
        description: String?,
    )

    // ==================== HELPER QUERIES FOR DENORMALIZATION ====================

    /**
     * Get primary author name for a book.
     *
     * Returns the first author found. Books may have multiple authors,
     * but we only index the first one for search simplicity.
     */
    @Query(
        """
        SELECT c.name FROM contributors c
        INNER JOIN book_contributors bc ON bc.contributorId = c.id
        WHERE bc.bookId = :bookId AND LOWER(bc.role) = 'author'
        LIMIT 1
    """,
    )
    suspend fun getPrimaryAuthorName(bookId: String): String?

    /**
     * Get primary narrator name for a book.
     */
    @Query(
        """
        SELECT c.name FROM contributors c
        INNER JOIN book_contributors bc ON bc.contributorId = c.id
        WHERE bc.bookId = :bookId AND LOWER(bc.role) = 'narrator'
        LIMIT 1
    """,
    )
    suspend fun getPrimaryNarratorName(bookId: String): String?

    /**
     * Get series names for a book (comma-separated, alphabetically sorted).
     *
     * Returns names of all series the book belongs to, joined with comma.
     * Used for FTS indexing to make books searchable by series name. The
     * `ORDER BY` makes FTS content deterministic across sync runs — same
     * joined string every time the index rebuilds.
     */
    @Query(
        """
        SELECT GROUP_CONCAT(s.name, ', ') FROM (
            SELECT s.name FROM series s
            INNER JOIN book_series bs ON s.id = bs.seriesId
            WHERE bs.bookId = :bookId
            ORDER BY s.name COLLATE NOCASE ASC
        ) s
    """,
    )
    suspend fun getSeriesNamesForBook(bookId: String): String?

    /**
     * Get genre names for a book (comma-separated, alphabetically sorted).
     *
     * Returns names of all genres attached to the book via the `book_genres`
     * junction, joined with `", "`. Used for FTS indexing to make books
     * searchable by genre name. Returns null when the book has no genres.
     * The `ORDER BY` makes FTS content deterministic across sync runs.
     */
    @Query(
        """
        SELECT GROUP_CONCAT(g.name, ', ') FROM (
            SELECT g.name FROM genres g
            INNER JOIN book_genres bg ON g.id = bg.genreId
            WHERE bg.bookId = :bookId
            ORDER BY g.name COLLATE NOCASE ASC
        ) g
    """,
    )
    suspend fun getGenreNamesForBook(bookId: String): String?
}
