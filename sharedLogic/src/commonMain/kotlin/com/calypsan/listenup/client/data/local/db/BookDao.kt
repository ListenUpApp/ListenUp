package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.Timestamp
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [BookEntity] operations.
 *
 * Provides both reactive (Flow-based) and one-shot queries for books.
 * All queries that return live data respect soft deletes — rows with a non-null
 * [BookEntity.deletedAt] are treated as tombstones and filtered out.
 * Use [softDelete] to apply a server tombstone; [deleteById] is a hard removal
 * for local-only cleanup scenarios.
 */
@Dao
@Suppress("TooManyFunctions")
interface BookDao {
    /**
     * Insert or update a book entity.
     * If a book with the same ID exists, it will be updated.
     *
     * @param book The book entity to upsert
     */
    @Upsert
    suspend fun upsert(book: BookEntity)

    /**
     * Insert or update multiple book entities in a single transaction.
     *
     * @param books List of book entities to upsert
     */
    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    /**
     * Get a single book by ID.
     * Returns null if book doesn't exist.
     *
     * @param id The type-safe book ID
     * @return The book entity or null
     */
    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: BookId): BookEntity?

    /**
     * Get all books synchronously.
     * Used by FtsPopulator to populate FTS tables during sync.
     *
     * @return List of all books
     */
    @Query("SELECT * FROM books")
    suspend fun getAll(): List<BookEntity>

    /**
     * Count total number of books in the database.
     * Used to detect sync mismatches (server has books but client doesn't).
     *
     * @return Total book count
     */
    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    /**
     * Observe all books as a reactive Flow.
     * Emits new list whenever any book changes.
     *
     * Used by UI to display book library with automatic updates.
     *
     * @return Flow emitting list of all books
     */
    @Query("SELECT * FROM books ORDER BY title ASC")
    fun observeAll(): Flow<List<BookEntity>>

    /**
     * Observe all books with their contributors as a reactive Flow.
     *
     * Uses Room Relations to efficiently load books and their contributors
     * in a single batched query, avoiding N+1 query problems.
     *
     * The @Transaction annotation ensures that the book and its related
     * contributors are loaded atomically.
     *
     * @return Flow emitting list of books with their contributors
     */
    @Transaction
    @Query("SELECT * FROM books ORDER BY title ASC")
    fun observeAllWithContributors(): Flow<List<BookWithContributors>>

    /**
     * Get a single book by ID with its contributors.
     *
     * Uses Room Relations to efficiently load the book and its contributors
     * in a single batched query.
     *
     * @param id The type-safe book ID
     * @return The book with contributors or null if not found
     */
    @Transaction
    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getByIdWithContributors(id: BookId): BookWithContributors?

    /**
     * Observe a single book by ID with its contributors as a reactive Flow.
     *
     * Emits null while the row is absent, emits the populated relation as soon
     * as it appears. Used as one of the upstream Flows composed by
     * [com.calypsan.listenup.client.data.repository.BookRepositoryImpl.observeBookDetail].
     *
     * @param id The type-safe book ID
     * @return Flow emitting the book with contributors, or null if absent
     */
    @Transaction
    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    fun observeByIdWithContributors(id: BookId): Flow<BookWithContributors?>

    /**
     * Get multiple books by IDs with their contributors in a single batched query.
     *
     * Uses Room Relations to efficiently load books and their contributors,
     * avoiding N+1 query problems when loading multiple books.
     *
     * @param ids List of type-safe book IDs
     * @return List of books with contributors (may be fewer than requested if some not found)
     */
    @Transaction
    @Query("SELECT * FROM books WHERE id IN (:ids)")
    suspend fun getByIdsWithContributors(ids: List<BookId>): List<BookWithContributors>

    /**
     * Reactive counterpart to [getByIdsWithContributors] — emits whenever any of
     * the requested book rows (or their contributor cross-refs) change.
     *
     * Used by [com.calypsan.listenup.client.data.repository.BookRepositoryImpl.observeBookListItems]
     * (ids overload) to power the Home → Continue Listening join: once position IDs
     * are known, this Flow keeps the book projections live so `flatMapLatest` in
     * [com.calypsan.listenup.client.data.repository.HomeRepositoryImpl] produces a stable
     * `List<ContinueListeningItem>` that updates as books sync into Room.
     *
     * @param ids List of type-safe book IDs to observe
     * @return Flow emitting the current set of matching books with contributors; re-emits on any change
     */
    @Transaction
    @Query("SELECT * FROM books WHERE id IN (:ids)")
    fun observeByIdsWithContributors(ids: List<BookId>): Flow<List<BookWithContributors>>

    /**
     * Hard-delete a book row by ID.
     *
     * Removes the row entirely. Use [softDelete] for server-originated tombstones,
     * which retain the row so the sync engine can track deletion state.
     *
     * @param id Type-safe book ID to delete
     */
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: BookId)

    /**
     * Delete multiple books by their IDs in a single transaction.
     *
     * More efficient than calling deleteById in a loop when handling
     * batch deletions from sync operations.
     *
     * @param ids List of book IDs to delete
     */
    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<BookId>)

    /**
     * Delete all books.
     * Used for testing and full re-sync scenarios.
     */
    @Query("DELETE FROM books")
    suspend fun deleteAll()

    /**
     * Live (non-tombstoned) book ids — used by the access-change reconcile to compute the
     * set of rows that must be evicted when the caller's accessible set shrinks.
     */
    @Query("SELECT id FROM books WHERE deletedAt IS NULL")
    suspend fun liveIds(): List<String>

    /**
     * Tombstone every live book whose id is NOT in [accessibleIds].
     *
     * Local-only eviction for the access-change reconcile: a book the caller can no longer
     * see is soft-deleted (`deletedAt` stamped) so the UI drops it, while accessible rows
     * are left untouched. The existing `revision` is preserved — this is not a server
     * tombstone, so there is no new revision to record. `updatedAt` advances so reactive
     * queries re-emit.
     */
    @Query("UPDATE books SET deletedAt = :now, updatedAt = :now WHERE deletedAt IS NULL AND id NOT IN (:accessibleIds)")
    suspend fun tombstoneNotIn(
        accessibleIds: Collection<String>,
        now: Long,
    )

    /**
     * Apply an own-echo: bump only the sync substrate fields on the book row.
     *
     * When a sync event echoes the client's own write, the visible fields are already
     * correct locally — repainting them would flicker the UI. This advances
     * [BookEntity.revision] and [BookEntity.updatedAt] only, leaving everything else
     * (title, cover, palette) untouched.
     *
     * @param id Type-safe book ID
     * @param revision New server revision
     * @param updatedAt New server update timestamp
     */
    @Query("UPDATE books SET revision = :revision, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateRevisionAndTimestamp(
        id: BookId,
        revision: Long,
        updatedAt: Timestamp,
    )

    /**
     * Soft-delete a book by stamping its tombstone, advancing its revision, and recording
     * the modification time.
     *
     * The row is retained — the UI filters on `deletedAt IS NULL`. Used when a
     * sync tombstone arrives for a book. `updatedAt` advances alongside `deletedAt`
     * because a soft-delete is a modification — consistent with [updateRevisionAndTimestamp].
     *
     * @param id Type-safe book ID
     * @param deletedAt Epoch-ms tombstone time (also used as `updatedAt`)
     * @param revision New server revision
     */
    @Query("UPDATE books SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(
        id: BookId,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Touch a book's updatedAt timestamp to trigger Flow re-emission.
     *
     * Used after cover downloads to force UI updates when cover files
     * appear on disk (even though database content hasn't changed).
     *
     * @param id Type-safe book ID to touch
     */
    @Query("UPDATE books SET updatedAt = :timestamp WHERE id = :id")
    suspend fun touchUpdatedAt(
        id: BookId,
        timestamp: Timestamp,
    )

    /**
     * Observe all books belonging to a specific series.
     *
     * Returns books ordered by series sequence (position in series) if available,
     * then by title as fallback. Used for series detail screens and animated
     * cover stacks.
     *
     * @param seriesId The series ID to filter by
     * @return Flow emitting list of books in the series
     */
    @Query(
        """
        SELECT b.* FROM books b
        INNER JOIN book_series bs ON b.id = bs.bookId
        WHERE bs.seriesId = :seriesId
        ORDER BY bs.sequence ASC, b.title ASC
    """,
    )
    fun observeBySeriesId(seriesId: String): Flow<List<BookEntity>>

    /**
     * Observe all books with their contributors filtered by series.
     *
     * Uses Room Relations to efficiently load books and their contributors
     * in a single batched query for a specific series.
     *
     * @param seriesId The series ID to filter by
     * @return Flow emitting list of books with their contributors
     */
    @Transaction
    @Query(
        """
        SELECT b.* FROM books b
        INNER JOIN book_series bs ON b.id = bs.bookId
        WHERE bs.seriesId = :seriesId
        ORDER BY bs.sequence ASC, b.title ASC
    """,
    )
    fun observeBySeriesIdWithContributors(seriesId: String): Flow<List<BookWithContributors>>

    /**
     * Observe all books for a specific contributor in a specific role.
     *
     * Used for contributor detail pages to show books grouped by role.
     * Results are ordered by title (series ordering handled in UI/domain layer).
     *
     * @param contributorId The contributor's unique ID
     * @param role The role to filter by (e.g., "author", "narrator")
     * @return Flow emitting list of books with their contributors
     */
    @Transaction
    @Query(
        """
        SELECT b.* FROM books b
        INNER JOIN book_contributors bc ON b.id = bc.bookId
        WHERE bc.contributorId = :contributorId AND bc.role = :role
        ORDER BY b.title ASC
    """,
    )
    fun observeByContributorAndRole(
        contributorId: String,
        role: String,
    ): Flow<List<BookWithContributors>>

    // ========== Discovery Queries ==========

    /**
     * Observe recently added books, newest first.
     * Used for "Recently Added" section on Discover screen.
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of recently added books
     */
    @Query(
        """
        SELECT * FROM books
        ORDER BY createdAt DESC
        LIMIT :limit
    """,
    )
    fun observeRecentlyAdded(limit: Int = 10): Flow<List<BookEntity>>

    /**
     * Observe random unstarted books with no series-sequence filter.
     *
     * Neutral query: returns every unstarted book regardless of series position.
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of random unstarted books
     */
    @Query(
        """
        SELECT b.* FROM books b
        LEFT JOIN playback_positions p ON b.id = p.bookId
        WHERE (p.bookId IS NULL OR p.positionMs = 0)
        ORDER BY RANDOM()
        LIMIT :limit
    """,
    )
    fun observeRandomUnstartedBooks(limit: Int = 10): Flow<List<BookEntity>>

    // ========== Discovery Queries with Author ==========

    /**
     * Observe recently added books with primary author, newest first.
     *
     * Neutral query: returns every book ordered by `createdAt` DESC with no series-sequence
     * filter.
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of recently added books with author
     */
    @Query(
        """
        SELECT
            b.id, b.title, b.coverBlurHash, b.createdAt,
            (
                SELECT c.name FROM book_contributors bc
                INNER JOIN contributors c ON bc.contributorId = c.id
                WHERE bc.bookId = b.id AND bc.role = 'author'
                LIMIT 1
            ) as authorName
        FROM books b
        ORDER BY b.createdAt DESC
        LIMIT :limit
    """,
    )
    fun observeRecentlyAddedWithAuthor(limit: Int = 10): Flow<List<DiscoveryBookWithAuthor>>

    /**
     * Observe random unstarted books with primary author, with no series-sequence filter.
     *
     * Neutral query: returns every unstarted book regardless of series position.
     *
     * @param limit Maximum number of books to return
     * @return Flow emitting list of random unstarted books with author
     */
    @Query(
        """
        SELECT
            b.id, b.title, b.coverBlurHash, b.createdAt,
            (
                SELECT c.name FROM book_contributors bc
                INNER JOIN contributors c ON bc.contributorId = c.id
                WHERE bc.bookId = b.id AND bc.role = 'author'
                LIMIT 1
            ) as authorName
        FROM books b
        LEFT JOIN playback_positions p ON b.id = p.bookId
        WHERE (p.bookId IS NULL OR p.positionMs = 0)
        ORDER BY RANDOM()
        LIMIT :limit
    """,
    )
    fun observeRandomUnstartedBooksWithAuthor(limit: Int = 10): Flow<List<DiscoveryBookWithAuthor>>

    /** All rows (including tombstones) with [revision][BookEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM books WHERE revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>
}

/**
 * Lightweight book data for discovery sections.
 * Includes only the fields needed for display: ID, title, blurHash, createdAt, and author.
 */
data class DiscoveryBookWithAuthor(
    val id: BookId,
    val title: String,
    val coverBlurHash: String?,
    val createdAt: Timestamp,
    val authorName: String?,
)
