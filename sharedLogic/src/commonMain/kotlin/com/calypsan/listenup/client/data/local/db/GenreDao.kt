package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Upsert
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [GenreEntity] and [BookGenreCrossRef] operations.
 *
 * Provides both reactive (Flow-based) and one-shot queries for genres.
 * Genres are system-defined hierarchical categories.
 */
@Dao
internal interface GenreDao {
    // ========== Genre Entity Operations ==========

    /**
     * Get all live genres ordered by path (hierarchical order). Tombstoned
     * genres (`deletedAt` non-null) are excluded.
     *
     * @return Flow emitting list of all live genres
     */
    @Query("SELECT * FROM genres WHERE deletedAt IS NULL ORDER BY path ASC, sortOrder ASC")
    fun observeAllGenres(): Flow<List<GenreEntity>>

    /**
     * Observe all live genres alongside their JOIN-derived bookCount. The count
     * comes from the `book_genres` junction restricted to live (non-tombstoned)
     * books — there is no denormalized column. Junction rows are retained when a
     * book is tombstoned (so a revived book gets its genres back without a full
     * aggregate re-send), so the `INNER JOIN books … deletedAt IS NULL` is what
     * keeps the count honest. UI surfaces that want a per-genre count (Admin
     * Categories, Browse Genres) consume this projection.
     *
     * @return Flow emitting `(GenreEntity, bookCount)` rows ordered by path.
     */
    @Query(
        """
        SELECT g.*, COALESCE(c.cnt, 0) AS bookCount
        FROM genres g
        LEFT JOIN (
            SELECT bg.genreId, COUNT(*) AS cnt
            FROM book_genres bg
            INNER JOIN books b ON b.id = bg.bookId AND b.deletedAt IS NULL
            GROUP BY bg.genreId
        ) c ON c.genreId = g.id
        WHERE g.deletedAt IS NULL
        ORDER BY g.path ASC, g.sortOrder ASC
    """,
    )
    fun observeAllGenresWithBookCount(): Flow<List<GenreWithBookCount>>

    /**
     * Get all live genres synchronously, ordered by path. Tombstoned genres
     * are excluded.
     *
     * @return List of all live genres
     */
    @Query("SELECT * FROM genres WHERE deletedAt IS NULL ORDER BY path ASC, sortOrder ASC")
    suspend fun getAllGenres(): List<GenreEntity>

    /**
     * Resolve genre names to (id, name) pairs, case-insensitive.
     *
     * Used by sync paths to map server-sent genre names to local genre IDs.
     * Names without a matching [GenreEntity] row are simply absent from the result;
     * callers are responsible for logging unresolved names.
     *
     * @param names Genre display names to resolve
     * @return Rows for names that matched; unmatched names are absent
     */
    @Query("SELECT id, name FROM genres WHERE name COLLATE NOCASE IN (:names)")
    suspend fun getIdsByNames(names: List<String>): List<GenreIdName>

    /**
     * Get a genre by ID.
     *
     * @param id The genre ID
     * @return The genre entity or null if not found
     */
    @Query("SELECT * FROM genres WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): GenreEntity?

    /**
     * Get a genre by slug.
     *
     * @param slug The genre slug
     * @return The genre entity or null if not found
     */
    @Query("SELECT * FROM genres WHERE slug = :slug AND deletedAt IS NULL")
    suspend fun getBySlug(slug: String): GenreEntity?

    /**
     * Get genres by path prefix (for hierarchical filtering).
     *
     * @param pathPrefix The path prefix (e.g., "/fiction/fantasy")
     * @return List of genres matching the path prefix
     */
    @Query("SELECT * FROM genres WHERE path LIKE :pathPrefix || '%' ORDER BY path ASC")
    suspend fun getByPathPrefix(pathPrefix: String): List<GenreEntity>

    /**
     * Insert or update a genre entity.
     *
     * @param genre The genre entity to upsert
     */
    @Upsert
    suspend fun upsert(genre: GenreEntity)

    /**
     * Insert or update multiple genre entities.
     *
     * @param genres List of genre entities to upsert
     */
    @Upsert
    suspend fun upsertAll(genres: List<GenreEntity>)

    /**
     * Soft-delete a genre — set the substrate tombstone columns. The row stays
     * (so revision bookkeeping survives); reads filter on `deletedAt IS NULL`.
     * Used by [com.calypsan.listenup.client.data.sync.domains.genresDomain]
     * to apply server `SyncEvent.Deleted`.
     */
    @Query("UPDATE genres SET deletedAt = :deletedAt, revision = :revision WHERE id = :id")
    suspend fun softDelete(
        id: String,
        deletedAt: Long,
        revision: Long,
    )

    /**
     * Count the live (non-tombstoned) direct children of [parentId] — the client mirror of the
     * server's `deleteGenre` precondition (a genre with live descendants can't be deleted). The
     * offline-first delete pre-validates this rule before writing/enqueuing so an offline delete of a
     * non-leaf genre fails the same way an online one would, with no optimistic write to roll back.
     */
    @Query("SELECT COUNT(*) FROM genres WHERE parentId = :parentId AND deletedAt IS NULL")
    suspend fun liveChildCount(parentId: String): Int

    /**
     * Hard-delete a genre by ID. Used for testing and full re-sync scenarios
     * only — production sync uses [softDelete].
     *
     * @param id The genre ID to delete
     */
    @Query("DELETE FROM genres WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete all genres.
     * Used for full re-sync scenarios.
     */
    @Query("DELETE FROM genres")
    suspend fun deleteAll()

    /** All rows (including tombstones) with [revision][GenreEntity.revision] <= [max], for digest computation. */
    @Query("SELECT id AS id, revision FROM genres WHERE deletedAt IS NULL AND revision <= :max")
    suspend fun digestRows(max: Long): List<IdRevision>

    /** The stored revision of the row with [id], tombstones included; null when the row has never been seen. */
    @Query("SELECT revision FROM genres WHERE id = :id LIMIT 1")
    suspend fun revisionOf(id: String): Long?

    // ========== Book-Genre Relationship Operations ==========

    /**
     * Insert multiple book-genre relationships.
     *
     * @param crossRefs List of book-genre relationships to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBookGenres(crossRefs: List<BookGenreCrossRef>)

    /**
     * Delete a book-genre relationship.
     *
     * @param bookId The book ID
     * @param genreId The genre ID
     */
    @Query("DELETE FROM book_genres WHERE bookId = :bookId AND genreId = :genreId")
    suspend fun deleteBookGenre(
        bookId: BookId,
        genreId: String,
    )

    /**
     * Delete all genres for a book.
     * Used when syncing to replace all genres.
     *
     * @param bookId The book ID
     */
    @Query("DELETE FROM book_genres WHERE bookId = :bookId")
    suspend fun deleteGenresForBook(bookId: BookId)

    /**
     * Delete all genres for multiple books.
     * Used by sync to batch-delete before re-inserting.
     *
     * @param bookIds List of book IDs
     */
    @Query("DELETE FROM book_genres WHERE bookId IN (:bookIds)")
    suspend fun deleteGenresForBooks(bookIds: List<BookId>)

    /**
     * Get all genres for a book.
     *
     * @param bookId The book ID
     * @return List of genres for the book
     */
    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query(
        """
        SELECT * FROM genres
        INNER JOIN book_genres ON genres.id = book_genres.genreId
        WHERE book_genres.bookId = :bookId
        ORDER BY genres.path ASC
    """,
    )
    suspend fun getGenresForBook(bookId: BookId): List<GenreEntity>

    /**
     * Observe all genres for a book reactively.
     *
     * @param bookId The book ID
     * @return Flow emitting list of genres for the book
     */
    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query(
        """
        SELECT * FROM genres
        INNER JOIN book_genres ON genres.id = book_genres.genreId
        WHERE book_genres.bookId = :bookId
        ORDER BY genres.path ASC
    """,
    )
    fun observeGenresForBook(bookId: BookId): Flow<List<GenreEntity>>

    /**
     * Delete all book-genre relationships.
     * Used for testing and full re-sync scenarios.
     */
    @Query("DELETE FROM book_genres")
    suspend fun deleteAllBookGenres()

    /**
     * Observe a genre by ID reactively.
     *
     * @param id The genre ID
     * @return Flow emitting the genre entity or null
     */
    @Query("SELECT * FROM genres WHERE id = :id AND deletedAt IS NULL")
    fun observeById(id: String): Flow<GenreEntity?>

    /**
     * Get all book IDs for a genre.
     *
     * @param genreId The genre ID
     * @return List of book IDs
     */
    @Query("SELECT bookId FROM book_genres WHERE genreId = :genreId")
    suspend fun getBookIdsForGenre(genreId: String): List<BookId>

    /**
     * Cascade-remove every `book_genres` link for [genreId] — the client mirror of the server's
     * `deleteGenre` cascade (which hard-deletes the junction rows, then re-derives each affected
     * book's genre list). Unlike `book_tags`/`book_series`, this junction carries no soft-delete
     * columns, so a hard delete is the faithful mirror; the authoritative genre list re-arrives on
     * each affected book's `book.Updated` echo. Keeps the optimistic delete honest: a soft-deleted
     * genre would otherwise still show on a book, since [getGenresForBook] does not filter tombstones.
     */
    @Query("DELETE FROM book_genres WHERE genreId = :genreId")
    suspend fun deleteAllBookGenresForGenre(genreId: String)

    /**
     * Replace all genres for a book atomically.
     *
     * @param bookId The book ID
     * @param genreIds List of genre IDs to set
     */
    @Transaction
    suspend fun replaceGenresForBook(
        bookId: BookId,
        genreIds: List<String>,
    ) {
        deleteGenresForBook(bookId)
        if (genreIds.isNotEmpty()) {
            val crossRefs = genreIds.map { BookGenreCrossRef(bookId = bookId, genreId = it) }
            insertAllBookGenres(crossRefs)
        }
    }

    /**
     * Fetch genre names for a batch of books in one query.
     *
     * Used by [com.calypsan.listenup.client.data.repository.StatsRepositoryImpl]
     * to compute the genre breakdown for the home screen stats. A JOIN through
     * the `book_genres` junction is more efficient than N separate
     * [getGenresForBook] calls when the batch is large.
     *
     * Books with no genres are absent from the returned map. An empty [bookIds]
     * set returns an empty map without touching the database.
     *
     * @param bookIds Set of book IDs to look up.
     * @return Map from bookId (String) to the list of genre display names for
     *   that book, ordered by [GenreEntity.path].
     */
    suspend fun getGenresForBooks(bookIds: Set<String>): Map<String, List<String>> {
        if (bookIds.isEmpty()) return emptyMap()
        return getBookGenreNamePairs(bookIds.toList())
            .groupBy({ it.bookId }, { it.genreName })
    }

    @Query(
        """
        SELECT bg.bookId, g.name AS genreName
        FROM book_genres bg
        INNER JOIN genres g ON g.id = bg.genreId
        WHERE bg.bookId IN (:bookIds)
        ORDER BY bg.bookId, g.path ASC
    """,
    )
    suspend fun getBookGenreNamePairs(bookIds: List<String>): List<BookGenreNamePair>
}

/**
 * Projection used by [GenreDao.getGenresForBooks] to join the `book_genres`
 * junction against `genres` and return one flat row per (book, genre) pair.
 */
internal data class BookGenreNamePair(
    val bookId: String,
    val genreName: String,
)

/**
 * Projection for resolving genre names to IDs during sync.
 *
 * Server responses ([BookResponse.genres]) carry genre names only; the client
 * maintains genre IDs via [GenreEntity]. This projection is the minimal shape
 * Sync domain handlers need to build [BookGenreCrossRef] rows.
 */
internal data class GenreIdName(
    val id: String,
    val name: String,
)

/**
 * Projection returned by [GenreDao.observeAllGenresWithBookCount]: the genre
 * row plus its current live-junction count. Embedding keeps the SQL column
 * names aligned with [GenreEntity] without a manual mapping.
 */
internal data class GenreWithBookCount(
    @androidx.room.Embedded val genre: GenreEntity,
    val bookCount: Int,
)
