package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.SkipQueryVerification
import kotlinx.coroutines.flow.Flow

/**
 * Result class for book search that includes denormalized author name.
 */
internal data class BookSearchResult(
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
 * - contributors_fts: contributorId, name, sortName, aliases, description
 * - series_fts: seriesId, name, description
 */
@Dao
internal interface SearchDao {
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
          AND b.deletedAt IS NULL
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
     * Search tags by name or slug (LIKE).
     *
     * Excludes tombstoned tags ([TagEntity.deletedAt] non-null).
     * Ordered by name for stable presentation.
     *
     * @param query Search pattern (will be wrapped in % for LIKE)
     * @param limit Max results to return
     */
    @Query(
        """
        SELECT * FROM tags
        WHERE (name LIKE '%' || :query || '%' OR slug LIKE '%' || :query || '%')
          AND deletedAt IS NULL
        ORDER BY name ASC
        LIMIT :limit
    """,
    )
    suspend fun searchTags(
        query: String,
        limit: Int = 10,
    ): List<TagEntity>

    // ==================== FTS POPULATION ====================

    /**
     * Count the rows in the book FTS index. Drives the startup self-heal: a populated library
     * with an empty index (e.g. an install that pre-dates index population) triggers a rebuild.
     */
    @SkipQueryVerification
    @Query("SELECT COUNT(*) FROM books_fts")
    suspend fun countBooksFts(): Int

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
        INSERT INTO contributors_fts (contributorId, name, sortName, aliases, description)
        VALUES (:contributorId, :name, :sortName, :aliases, :description)
    """,
    )
    suspend fun insertContributorFts(
        contributorId: String,
        name: String,
        sortName: String?,
        aliases: String?,
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

    // ==================== BATCH QUERIES FOR FULL REBUILD ====================

    /**
     * Fetch the alphabetically-first author name for every book that has at
     * least one author contributor, in a single query.
     *
     * Using [MIN] over contributor names rather than an arbitrary `LIMIT 1`
     * produces a deterministic result when a book has multiple authors — the
     * same author is always chosen across rebuild runs. The per-book
     * [getPrimaryAuthorName] uses `LIMIT 1` without ordering, which has the
     * same single-author semantics but is non-deterministic for multi-author
     * books; this batch variant is strictly more consistent.
     *
     * @return List of `(bookId, authorName)` pairs — one row per book that has an author.
     *   Books with no author contributor are absent from the result.
     */
    @Query(
        """
        SELECT bc.bookId AS bookId, MIN(c.name) AS authorName
        FROM contributors c
        INNER JOIN book_contributors bc ON bc.contributorId = c.id
        WHERE LOWER(bc.role) = 'author'
        GROUP BY bc.bookId
    """,
    )
    suspend fun getAllPrimaryAuthorNames(): List<BookIdNameRow>

    /**
     * Fetch the alphabetically-first narrator name for every book that has at
     * least one narrator contributor, in a single query.
     *
     * Mirrors [getAllPrimaryAuthorNames] with `role = 'narrator'`.
     *
     * @return List of `(bookId, authorName)` pairs — one row per book that has a narrator.
     *   Books with no narrator are absent.
     */
    @Query(
        """
        SELECT bc.bookId AS bookId, MIN(c.name) AS authorName
        FROM contributors c
        INNER JOIN book_contributors bc ON bc.contributorId = c.id
        WHERE LOWER(bc.role) = 'narrator'
        GROUP BY bc.bookId
    """,
    )
    suspend fun getAllPrimaryNarratorNames(): List<BookIdNameRow>

    /**
     * Fetch the comma-joined, alphabetically-sorted series names for every
     * book that belongs to at least one series, in a single query.
     *
     * Reproduces exactly the `ORDER BY name COLLATE NOCASE ASC` + `GROUP_CONCAT`
     * logic of [getSeriesNamesForBook], extended across all books at once.
     * The inner `ORDER BY` is applied before `GROUP_CONCAT` via the subquery,
     * so the joined string is identical to what the per-book query would return.
     *
     * @return List of `(bookId, authorName)` pairs — one row per book that belongs to a series.
     *   Books in no series are absent.
     */
    @Query(
        """
        SELECT bookId, GROUP_CONCAT(name, ', ') AS authorName
        FROM (
            SELECT bs.bookId AS bookId, s.name AS name
            FROM series s
            INNER JOIN book_series bs ON s.id = bs.seriesId
            ORDER BY bs.bookId, s.name COLLATE NOCASE ASC
        )
        GROUP BY bookId
    """,
    )
    suspend fun getAllSeriesNamesGrouped(): List<BookIdNameRow>

    /**
     * Fetch the comma-joined, alphabetically-sorted genre names for every
     * book that has at least one genre, in a single query.
     *
     * Mirrors [getAllSeriesNamesGrouped] for the `book_genres` junction.
     *
     * @return List of `(bookId, authorName)` pairs — one row per book that has a genre.
     *   Books with no genre are absent.
     */
    @Query(
        """
        SELECT bookId, GROUP_CONCAT(name, ', ') AS authorName
        FROM (
            SELECT bg.bookId AS bookId, g.name AS name
            FROM genres g
            INNER JOIN book_genres bg ON g.id = bg.genreId
            ORDER BY bg.bookId, g.name COLLATE NOCASE ASC
        )
        GROUP BY bookId
    """,
    )
    suspend fun getAllGenreNamesGrouped(): List<BookIdNameRow>

    // ==================== INCREMENTAL REINDEX QUERIES ====================

    /** Highest `revision` currently in the `books` table — the pre-reconcile watermark. */
    @Query("SELECT COALESCE(MAX(revision), 0) FROM books")
    suspend fun maxBookRevision(): Long

    /** Highest `revision` currently in the `contributors` table — the pre-reconcile watermark. */
    @Query("SELECT COALESCE(MAX(revision), 0) FROM contributors")
    suspend fun maxContributorRevision(): Long

    /** Highest `revision` currently in the `series` table — the pre-reconcile watermark. */
    @Query("SELECT COALESCE(MAX(revision), 0) FROM series")
    suspend fun maxSeriesRevision(): Long

    /** Highest `revision` currently in the `genres` table — the pre-reconcile watermark. */
    @Query("SELECT COALESCE(MAX(revision), 0) FROM genres")
    suspend fun maxGenreRevision(): Long

    /**
     * A change signal for the searchable content tables. Room re-emits on ANY write to `books`,
     * `contributors`, `series`, or `genres` (the tables [refreshSince][com.calypsan.listenup.client.data.sync.FtsPopulatorContract.refreshSince]
     * reindexes from), so a live firehose edit that lands in Room drives a debounced FTS refresh — the
     * emitted value is irrelevant, only the invalidation matters. Table-level, so an UPDATE (a title
     * edit, a revision bump) fires it too, not only inserts/deletes.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM books) + (SELECT COUNT(*) FROM contributors) + " +
            "(SELECT COUNT(*) FROM series) + (SELECT COUNT(*) FROM genres)",
    )
    fun observeSearchableContentSignal(): Flow<Long>

    /** Ids of books whose own row changed since [revision] (includes tombstones — soft-delete bumps revision). */
    @Query("SELECT id FROM books WHERE revision > :revision")
    suspend fun bookIdsChangedSince(revision: Long): List<String>

    /** Ids of books whose linked contributor changed since [revision] (e.g. an author rename). */
    @Query(
        """
        SELECT DISTINCT bc.bookId FROM book_contributors bc
        INNER JOIN contributors c ON c.id = bc.contributorId
        WHERE c.revision > :revision
    """,
    )
    suspend fun bookIdsWithContributorsChangedSince(revision: Long): List<String>

    /** Ids of books whose linked series changed since [revision] (e.g. a series rename). */
    @Query(
        """
        SELECT DISTINCT bs.bookId FROM book_series bs
        INNER JOIN series s ON s.id = bs.seriesId
        WHERE s.revision > :revision
    """,
    )
    suspend fun bookIdsWithSeriesChangedSince(revision: Long): List<String>

    /** Ids of books whose linked genre changed since [revision] (e.g. a genre rename). */
    @Query(
        """
        SELECT DISTINCT bg.bookId FROM book_genres bg
        INNER JOIN genres g ON g.id = bg.genreId
        WHERE g.revision > :revision
    """,
    )
    suspend fun bookIdsWithGenresChangedSince(revision: Long): List<String>

    /** Count of contributors changed since [revision] — drives the contributors_fts rebuild decision. */
    @Query("SELECT COUNT(*) FROM contributors WHERE revision > :revision")
    suspend fun countContributorsChangedSince(revision: Long): Int

    /** Count of series changed since [revision] — drives the series_fts rebuild decision. */
    @Query("SELECT COUNT(*) FROM series WHERE revision > :revision")
    suspend fun countSeriesChangedSince(revision: Long): Int

    /** Delete the books_fts rows for exactly [bookIds] — the targeted counterpart to [clearBooksFts]. */
    @SkipQueryVerification
    @Query("DELETE FROM books_fts WHERE bookId IN (:bookIds)")
    suspend fun deleteBookFtsEntries(bookIds: List<String>)

    /** Live (non-tombstoned) books among [ids] — the source rows for a targeted FTS reindex. */
    @Query("SELECT * FROM books WHERE id IN (:ids) AND deletedAt IS NULL")
    suspend fun getLiveBooksByIds(ids: List<String>): List<BookEntity>

    /** [getAllPrimaryAuthorNames] scoped to [bookIds] — for a targeted reindex of just the changed books. */
    @Query(
        """
        SELECT bc.bookId AS bookId, MIN(c.name) AS authorName
        FROM contributors c
        INNER JOIN book_contributors bc ON bc.contributorId = c.id
        WHERE LOWER(bc.role) = 'author' AND bc.bookId IN (:bookIds)
        GROUP BY bc.bookId
    """,
    )
    suspend fun getPrimaryAuthorNamesFor(bookIds: List<String>): List<BookIdNameRow>

    /** [getAllPrimaryNarratorNames] scoped to [bookIds] — for a targeted reindex of just the changed books. */
    @Query(
        """
        SELECT bc.bookId AS bookId, MIN(c.name) AS authorName
        FROM contributors c
        INNER JOIN book_contributors bc ON bc.contributorId = c.id
        WHERE LOWER(bc.role) = 'narrator' AND bc.bookId IN (:bookIds)
        GROUP BY bc.bookId
    """,
    )
    suspend fun getPrimaryNarratorNamesFor(bookIds: List<String>): List<BookIdNameRow>

    /** [getAllSeriesNamesGrouped] scoped to [bookIds] — for a targeted reindex of just the changed books. */
    @Query(
        """
        SELECT bookId, GROUP_CONCAT(name, ', ') AS authorName
        FROM (
            SELECT bs.bookId AS bookId, s.name AS name
            FROM series s
            INNER JOIN book_series bs ON s.id = bs.seriesId
            WHERE bs.bookId IN (:bookIds)
            ORDER BY bs.bookId, s.name COLLATE NOCASE ASC
        )
        GROUP BY bookId
    """,
    )
    suspend fun getSeriesNamesGroupedFor(bookIds: List<String>): List<BookIdNameRow>

    /** [getAllGenreNamesGrouped] scoped to [bookIds] — for a targeted reindex of just the changed books. */
    @Query(
        """
        SELECT bookId, GROUP_CONCAT(name, ', ') AS authorName
        FROM (
            SELECT bg.bookId AS bookId, g.name AS name
            FROM genres g
            INNER JOIN book_genres bg ON g.id = bg.genreId
            WHERE bg.bookId IN (:bookIds)
            ORDER BY bg.bookId, g.name COLLATE NOCASE ASC
        )
        GROUP BY bookId
    """,
    )
    suspend fun getGenreNamesGroupedFor(bookIds: List<String>): List<BookIdNameRow>
}

/**
 * A `(bookId, name)` projection returned by the batch FTS-rebuild queries.
 *
 * Each batch query returns one row per book: the book's ID and the
 * pre-aggregated value for a single dimension (author name, narrator name,
 * series names, or genre names). [FtsPopulator] assembles these into maps
 * keyed by [bookId] so it can look up each dimension in O(1) while
 * iterating the book list for FTS inserts.
 *
 * Column alias `authorName` is reused across all four batch queries so a
 * single data class serves all four projections without introducing four
 * identical types.
 *
 * @property bookId The book's primary key.
 * @property authorName The aggregated name string for this dimension (author,
 *   narrator, series names, or genre names depending on the calling query).
 */
internal data class BookIdNameRow(
    val bookId: String,
    val authorName: String,
)
