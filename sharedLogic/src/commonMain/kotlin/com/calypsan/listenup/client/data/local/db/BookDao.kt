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
 * List/browse queries (library, series, contributor, and Discover surfaces) respect
 * soft deletes — rows with a non-null [BookEntity.deletedAt] are filtered out, so a
 * tombstoned book never appears in a browse list. Point lookups by id (`getById`,
 * `getByIdWithContributors`, `observeByIdWithContributors`, the `*ByIds*` batch reads)
 * deliberately return the row regardless of tombstone state; the detail layer decides
 * how to react to a deleted book. [digestRows] excludes tombstones — the digest counts only
 * LIVE rows, symmetric with the server's tombstone-excluding digest, so a member who tombstoned
 * a book locally still converges (F1). Use [softDelete] to apply a server tombstone; [deleteById] is a hard removal
 * for local-only cleanup scenarios.
 */
@Dao
@Suppress("TooManyFunctions")
internal interface BookDao {
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
     * Get all live (non-tombstoned) books synchronously.
     * Used by FtsPopulator to populate FTS tables during sync — the search
     * index must not surface server-deleted books.
     *
     * @return List of all live books
     */
    @Query("SELECT * FROM books WHERE deletedAt IS NULL")
    suspend fun getAllLive(): List<BookEntity>

    /**
     * Count total number of books in the database.
     * Used to detect sync mismatches (server has books but client doesn't).
     *
     * @return Total book count
     */
    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    /**
     * Reactively true while no live (non-tombstoned) book exists. One half of the
     * initial-population ("Building your library") gate: only an empty library that the server
     * hasn't marked scan-complete is still "building".
     *
     * @return Flow emitting true when the live book set is empty, false once any book lands.
     */
    @Query("SELECT COUNT(*) = 0 FROM books WHERE deletedAt IS NULL")
    fun observeIsEmpty(): Flow<Boolean>

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
    @Query("SELECT * FROM books WHERE deletedAt IS NULL ORDER BY title ASC")
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
     * Tombstone the given live books by id — the chunked access-change prune.
     *
     * Local-only eviction: a book the caller can no longer see is soft-deleted (`deletedAt`
     * stamped) so the UI drops it, while accessible rows are left untouched. The existing
     * `revision` is preserved — this is not a server tombstone, so there is no new revision to
     * record. `updatedAt` advances so reactive queries re-emit. The composed handler computes the
     * doomed set in Kotlin and calls this with id chunks bounded under SQLite's bind-var ceiling.
     */
    @Query("UPDATE books SET deletedAt = :now, updatedAt = :now WHERE deletedAt IS NULL AND id IN (:ids)")
    suspend fun tombstoneByIds(
        ids: List<String>,
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
     * Mark a single book's cover as present on disk. Conditioned on the column
     * currently being unset so a redundant mark (the cover was already recorded) touches
     * zero rows — Room's invalidation tracker only wakes observers on an actual row change.
     *
     * @param id The book whose cover just landed on disk.
     * @param timestamp When the cover was recorded as downloaded.
     */
    @Query("UPDATE books SET coverDownloadedAt = :timestamp WHERE id = :id AND coverDownloadedAt IS NULL")
    suspend fun markCoverDownloaded(
        id: BookId,
        timestamp: Timestamp,
    )

    /**
     * Clear a single book's cover-presence marker (the local file was deleted or
     * invalidated). Conditioned on the column currently being set, for the same
     * no-op-touches-zero-rows reason as [markCoverDownloaded].
     *
     * @param id The book whose cover was removed or invalidated.
     */
    @Query("UPDATE books SET coverDownloadedAt = NULL WHERE id = :id AND coverDownloadedAt IS NOT NULL")
    suspend fun clearCoverDownloaded(id: BookId)

    /**
     * Ids of every book currently marked as having a local cover file. Used by the startup
     * cover-presence reconciler to diff the marker against the on-disk covers directory.
     */
    @Query("SELECT id FROM books WHERE coverDownloadedAt IS NOT NULL")
    suspend fun idsWithCoverMarked(): List<BookId>

    /**
     * Batch variant of [markCoverDownloaded] for the startup reconciler's backfill pass —
     * marks every id in [ids] whose column is currently unset in a single statement.
     *
     * @param ids Book ids found on disk but not yet marked in Room.
     * @param timestamp When the covers were recorded as downloaded.
     */
    @Query("UPDATE books SET coverDownloadedAt = :timestamp WHERE id IN (:ids) AND coverDownloadedAt IS NULL")
    suspend fun markCoversDownloaded(
        ids: List<BookId>,
        timestamp: Timestamp,
    )

    /**
     * Batch variant of [clearCoverDownloaded] for the startup reconciler's heal pass —
     * clears the marker for every id in [ids] (rows marked but missing on disk).
     *
     * @param ids Book ids marked in Room but absent from the on-disk covers directory.
     */
    @Query("UPDATE books SET coverDownloadedAt = NULL WHERE id IN (:ids)")
    suspend fun clearCoversDownloaded(ids: List<BookId>)

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
        WHERE bs.seriesId = :seriesId AND b.deletedAt IS NULL
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
        WHERE bs.seriesId = :seriesId AND b.deletedAt IS NULL
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
        WHERE bc.contributorId = :contributorId AND bc.role = :role AND b.deletedAt IS NULL
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
        WHERE deletedAt IS NULL
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
        WHERE (p.bookId IS NULL OR p.positionMs = 0) AND b.deletedAt IS NULL
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
            b.id, b.title, b.coverBlurHash, b.coverHash, b.coverDownloadedAt, b.createdAt,
            (
                SELECT c.name FROM book_contributors bc
                INNER JOIN contributors c ON bc.contributorId = c.id
                WHERE bc.bookId = b.id AND bc.role = 'author'
                LIMIT 1
            ) as authorName
        FROM books b
        WHERE b.deletedAt IS NULL
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
            b.id, b.title, b.coverBlurHash, b.coverHash, b.coverDownloadedAt, b.createdAt,
            (
                SELECT c.name FROM book_contributors bc
                INNER JOIN contributors c ON bc.contributorId = c.id
                WHERE bc.bookId = b.id AND bc.role = 'author'
                LIMIT 1
            ) as authorName
        FROM books b
        LEFT JOIN playback_positions p ON b.id = p.bookId
        WHERE (p.bookId IS NULL OR p.positionMs = 0) AND b.deletedAt IS NULL
        ORDER BY RANDOM()
        LIMIT :limit
    """,
    )
    fun observeRandomUnstartedBooksWithAuthor(limit: Int = 10): Flow<List<DiscoveryBookWithAuthor>>

    /**
     * Observe **all** unstarted books joined to their series sequences — one row per
     * (book, series-edge), with a `null` [DiscoveryBookWithSeries.sequence] for standalone
     * books (no `book_series` edge).
     *
     * Neutral query: no `LIMIT`, no series filter. Series-aware filtering and limiting are
     * applied in the repository (per the rule "query-shaping lives in the repository"), which
     * must filter the full candidate set *before* limiting.
     *
     * @return Flow emitting every unstarted (book × sequence) row.
     */
    @Query(
        """
        SELECT
            b.id, b.title, b.coverBlurHash, b.coverHash, b.coverDownloadedAt, b.createdAt,
            (
                SELECT c.name FROM book_contributors bc
                INNER JOIN contributors c ON bc.contributorId = c.id
                WHERE bc.bookId = b.id AND bc.role = 'author'
                LIMIT 1
            ) as authorName,
            bs.sequence as sequence
        FROM books b
        LEFT JOIN playback_positions p ON b.id = p.bookId
        LEFT JOIN book_series bs ON bs.bookId = b.id
        WHERE (p.bookId IS NULL OR p.positionMs = 0) AND b.deletedAt IS NULL
    """,
    )
    fun observeUnstartedCandidatesWithSeries(): Flow<List<DiscoveryBookWithSeries>>

    /**
     * Observe the distinct set of book ids that have at least one supplementary document.
     *
     * Used by [com.calypsan.listenup.client.data.repository.BookRepositoryImpl.observeBookListItems]
     * to combine document presence into [com.calypsan.listenup.client.domain.model.BookListItem.hasDocuments].
     * Re-emits whenever [book_documents] changes (a document is added or removed), so the
     * library grid badge stays live without any additional polling.
     *
     * @return Flow emitting the distinct [bookId] strings of books that own at least one document.
     */
    @Query("SELECT DISTINCT bookId FROM book_documents")
    fun observeBookIdsWithDocuments(): Flow<List<String>>

    /** LIVE rows (tombstones excluded) with [revision][BookEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM books WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM books WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: BookId): Long?

    /**
     * One-shot lightweight summary for a single book — title, cover blur hash, and primary
     * author — used to enrich social presence sessions from the viewer's local library.
     *
     * Returns null when the book is absent from the local mirror (the viewer cannot access it),
     * which the caller treats as "drop this session".
     *
     * @param id The book id to summarise.
     * @return The summary row, or null if the book is not present locally.
     */
    @Query(
        """
        SELECT
            b.id, b.title, b.coverBlurHash, b.coverHash,
            (
                SELECT c.name FROM book_contributors bc
                INNER JOIN contributors c ON bc.contributorId = c.id
                WHERE bc.bookId = b.id AND bc.role = 'author'
                LIMIT 1
            ) as authorName
        FROM books b
        WHERE b.id = :id AND b.deletedAt IS NULL
        LIMIT 1
    """,
    )
    suspend fun getBookSummary(id: String): BookSummary?
}

/**
 * Minimal book identity for enriching social presence sessions.
 *
 * Carries exactly what the "currently listening" UI needs beyond wire identity: the title,
 * the cover blur hash for the placeholder, the cover content hash for image-cache busting,
 * and the primary author's name.
 *
 * @property id The book id.
 * @property title The book title.
 * @property coverBlurHash BlurHash placeholder for the cover, or null when none is stored.
 * @property coverHash Content hash of the cover, used to version the image-cache key so a
 *   re-imaged cover invalidates the stale cached bitmap; null when no cover is stored.
 * @property authorName Primary author's display name, or null when no author is linked.
 */
internal data class BookSummary(
    val id: String,
    val title: String,
    val coverBlurHash: String?,
    val coverHash: String?,
    val authorName: String?,
)

/**
 * Lightweight book data for discovery sections.
 * Includes only the fields needed for display: ID, title, blurHash, createdAt, and author.
 */
internal data class DiscoveryBookWithAuthor(
    val id: BookId,
    val title: String,
    val coverBlurHash: String?,
    val coverHash: String?,
    val coverDownloadedAt: Timestamp?,
    val createdAt: Timestamp,
    val authorName: String?,
)

/**
 * Discovery candidate row carrying one book × one series-sequence edge.
 *
 * Standalone books (no series edge) produce a single row with [sequence] = `null`.
 * A book in N series produces N rows. The repository groups by [id] and applies the
 * series-starter filter before limiting.
 */
internal data class DiscoveryBookWithSeries(
    val id: BookId,
    val title: String,
    val coverBlurHash: String?,
    val coverHash: String?,
    val coverDownloadedAt: Timestamp?,
    val createdAt: Timestamp,
    val authorName: String?,
    val sequence: String?,
)
