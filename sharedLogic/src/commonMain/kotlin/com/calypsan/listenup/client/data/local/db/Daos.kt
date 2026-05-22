package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Upsert
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY name ASC")
    fun observeAll(): Flow<List<SeriesEntity>>

    /**
     * Get all series synchronously.
     * Used by FtsPopulator to populate FTS tables during sync.
     */
    @Query("SELECT * FROM series")
    suspend fun getAll(): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getById(id: String): SeriesEntity?

    /**
     * Observe a single series by ID.
     *
     * @param id The series ID
     * @return Flow emitting the series or null if not found
     */
    @Query("SELECT * FROM series WHERE id = :id")
    fun observeById(id: String): Flow<SeriesEntity?>

    /**
     * Observe the first series for a specific book.
     *
     * A book can belong to multiple series, but this returns only the first one.
     * Uses book_series junction table to find the relationship.
     *
     * @param bookId The book ID
     * @return Flow emitting the series or null if book has no series
     */
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT s.* FROM series s
        INNER JOIN book_series bs ON s.id = bs.seriesId
        WHERE bs.bookId = :bookId
        LIMIT 1
    """,
    )
    fun observeByBookId(bookId: String): Flow<SeriesEntity?>

    /**
     * Get all book IDs that belong to a specific series.
     *
     * @param seriesId The series ID
     * @return List of book IDs in this series
     */
    @Query("SELECT bookId FROM book_series WHERE seriesId = :seriesId")
    suspend fun getBookIdsForSeries(seriesId: String): List<String>

    /**
     * Observe all book IDs that belong to a specific series reactively.
     *
     * @param seriesId The series ID
     * @return Flow emitting list of book IDs in this series
     */
    @Query("SELECT bookId FROM book_series WHERE seriesId = :seriesId")
    fun observeBookIdsForSeries(seriesId: String): Flow<List<String>>

    @Upsert
    suspend fun upsert(series: SeriesEntity)

    @Upsert
    suspend fun upsertAll(series: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete multiple series by their IDs in a single transaction.
     *
     * More efficient than calling deleteById in a loop when handling
     * batch deletions from sync operations.
     *
     * @param ids List of series IDs to delete
     */
    @Query("DELETE FROM series WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Observe all series with their book counts.
     * Returns series ordered by name with the count of books in each series.
     */
    @Query(
        """
        SELECT s.*, COUNT(bs.bookId) as bookCount
        FROM series s
        LEFT JOIN book_series bs ON s.id = bs.seriesId
        GROUP BY s.id
        ORDER BY s.name ASC
    """,
    )
    fun observeAllWithBookCount(): Flow<List<SeriesWithBookCount>>

    /**
     * Observe all series with their books.
     * Uses Room Relations to batch-load all books for each series.
     * Books are ordered by series sequence then title within each series.
     */
    @Transaction
    @Query("SELECT * FROM series ORDER BY name ASC")
    fun observeAllWithBooks(): Flow<List<SeriesWithBooks>>

    /**
     * Get a single series by ID with all its books.
     */
    @Transaction
    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getByIdWithBooks(id: String): SeriesWithBooks?

    /**
     * Observe a single series by ID with all its books.
     */
    @Transaction
    @Query("SELECT * FROM series WHERE id = :id")
    fun observeByIdWithBooks(id: String): Flow<SeriesWithBooks?>

    @Query("SELECT COUNT(*) FROM series")
    suspend fun count(): Int

    @Query("DELETE FROM series")
    suspend fun deleteAll()

    /** Apply a server tombstone: set the soft-delete timestamp and revision. */
    @Query("UPDATE series SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(
        id: SeriesId,
        deletedAt: Long,
        revision: Long,
    )
}

@Dao
interface ContributorDao {
    @Query("SELECT * FROM contributors")
    fun observeAll(): Flow<List<ContributorEntity>>

    @Transaction
    @Query("SELECT * FROM contributors")
    fun observeAllWithAliases(): Flow<List<ContributorWithAliases>>

    @Transaction
    @Query("SELECT * FROM contributors WHERE id = :id")
    fun observeByIdWithAliases(id: String): Flow<ContributorWithAliases?>

    @Transaction
    @Query("SELECT * FROM contributors WHERE id = :id")
    suspend fun getByIdWithAliases(id: String): ContributorWithAliases?

    /**
     * Get all contributors synchronously.
     * Used by FtsPopulator to populate FTS tables during sync.
     */
    @Query("SELECT * FROM contributors")
    suspend fun getAll(): List<ContributorEntity>

    @Query("SELECT * FROM contributors WHERE id = :id")
    suspend fun getById(id: String): ContributorEntity?

    @Upsert
    suspend fun upsert(contributor: ContributorEntity)

    @Upsert
    suspend fun upsertAll(contributors: List<ContributorEntity>)

    @Query("DELETE FROM contributors WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete multiple contributors by their IDs in a single transaction.
     *
     * More efficient than calling deleteById in a loop when handling
     * batch deletions from sync operations.
     *
     * @param ids List of contributor IDs to delete
     */
    @Query("DELETE FROM contributors WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * Observe contributors filtered by role with their book counts.
     * Returns contributors who have the specified role on at least one book,
     * ordered by name with the count of books they're associated with.
     *
     * @param role The role to filter by (e.g., "author", "narrator")
     */
    @Query(
        """
        SELECT c.*, COUNT(bc.bookId) as bookCount
        FROM contributors c
        INNER JOIN book_contributors bc ON c.id = bc.contributorId
        WHERE bc.role = :role
        GROUP BY c.id
        ORDER BY c.name ASC
    """,
    )
    fun observeByRoleWithCount(role: String): Flow<List<ContributorWithBookCount>>

    /**
     * Observe a single contributor by ID.
     *
     * @param id The contributor's unique ID
     * @return Flow emitting the contributor or null if not found
     */
    @Query("SELECT * FROM contributors WHERE id = :id")
    fun observeById(id: String): Flow<ContributorEntity?>

    /**
     * Get all distinct roles a contributor has across their books.
     *
     * @param contributorId The contributor's unique ID
     * @return List of role strings (e.g., ["author", "narrator"])
     */
    @Query(
        """
        SELECT DISTINCT bc.role
        FROM book_contributors bc
        WHERE bc.contributorId = :contributorId
        ORDER BY bc.role ASC
    """,
    )
    suspend fun getRolesForContributor(contributorId: String): List<String>

    /**
     * Observe all roles a contributor has with book counts per role.
     *
     * @param contributorId The contributor's unique ID
     * @return Flow of role to book count pairs
     */
    @Query(
        """
        SELECT bc.role, COUNT(bc.bookId) as bookCount
        FROM book_contributors bc
        WHERE bc.contributorId = :contributorId
        GROUP BY bc.role
        ORDER BY bc.role ASC
    """,
    )
    fun observeRolesWithCountForContributor(contributorId: String): Flow<List<RoleWithBookCount>>

    /**
     * Update the local image path for a contributor.
     *
     * Called after downloading a contributor's image during sync.
     *
     * @param contributorId The contributor to update
     * @param imagePath Local file path to the downloaded image
     */
    @Query("UPDATE contributors SET imagePath = :imagePath WHERE id = :contributorId")
    suspend fun updateImagePath(
        contributorId: String,
        imagePath: String?,
    )

    @Query("SELECT COUNT(*) FROM contributors")
    suspend fun count(): Int

    @Query("DELETE FROM contributors")
    suspend fun deleteAll()

    /** Apply a server tombstone: set the soft-delete timestamp and revision. */
    @Query(
        "UPDATE contributors SET deletedAt = :deletedAt, revision = :revision, updatedAt = :deletedAt WHERE id = :id",
    )
    suspend fun softDelete(
        id: ContributorId,
        deletedAt: Long,
        revision: Long,
    )

    // =========================================================================
    // Book-Contributor Relationship Queries
    // =========================================================================

    /**
     * Observe all contributors for a specific book.
     *
     * Returns contributors for all roles (author, narrator, etc.).
     * Use getContributorsForBookByRole on BookContributorDao for role-specific queries.
     *
     * @param bookId The book ID
     * @return Flow emitting list of contributors for the book
     */
    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query(
        """
        SELECT * FROM contributors
        INNER JOIN book_contributors ON contributors.id = book_contributors.contributorId
        WHERE book_contributors.bookId = :bookId
        ORDER BY contributors.name ASC
    """,
    )
    fun observeByBookId(bookId: String): Flow<List<ContributorEntity>>

    /**
     * Get all contributors for a specific book synchronously.
     *
     * Returns contributors for all roles (author, narrator, etc.).
     *
     * @param bookId The book ID
     * @return List of contributors for the book
     */
    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query(
        """
        SELECT * FROM contributors
        INNER JOIN book_contributors ON contributors.id = book_contributors.contributorId
        WHERE book_contributors.bookId = :bookId
        ORDER BY contributors.name ASC
    """,
    )
    suspend fun getByBookId(bookId: String): List<ContributorEntity>

    /**
     * Get all book IDs for a specific contributor.
     *
     * @param contributorId The contributor ID
     * @return List of book IDs
     */
    @Query("SELECT DISTINCT bookId FROM book_contributors WHERE contributorId = :contributorId")
    suspend fun getBookIdsForContributor(contributorId: String): List<String>

    /**
     * Observe all book IDs for a specific contributor reactively.
     *
     * @param contributorId The contributor ID
     * @return Flow emitting list of book IDs
     */
    @Query("SELECT DISTINCT bookId FROM book_contributors WHERE contributorId = :contributorId")
    fun observeBookIdsForContributor(contributorId: String): Flow<List<String>>
}

/**
 * DAO for the [ContributorAliasCrossRef] junction.
 *
 * Reads are alphabetical and case-insensitive via `COLLATE NOCASE`. Writes
 * use `OnConflictStrategy.IGNORE` for exact-case duplicates; the repository
 * layer is responsible for case-insensitive dedup before calling `insertAll`.
 *
 * No `@Transaction`-annotated `replaceForContributor` — callers compose
 * `deleteForContributor` + `insertAll` inside their existing
 * `TransactionRunner.atomically { }` block to keep the transaction layer single.
 */
@Dao
interface ContributorAliasDao {
    @Query(
        "SELECT alias FROM contributor_aliases " +
            "WHERE contributorId = :id " +
            "ORDER BY alias COLLATE NOCASE ASC",
    )
    suspend fun getForContributor(id: String): List<String>

    @Query(
        "SELECT alias FROM contributor_aliases " +
            "WHERE contributorId = :id " +
            "ORDER BY alias COLLATE NOCASE ASC",
    )
    fun observeForContributor(id: String): Flow<List<String>>

    @Query("DELETE FROM contributor_aliases WHERE contributorId = :id")
    suspend fun deleteForContributor(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(aliases: List<ContributorAliasCrossRef>)
}
