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
 * Room DAO for [TagEntity] and [BookTagCrossRef] operations.
 *
 * Provides both reactive (Flow-based) and one-shot queries for tags.
 * Tags are community-wide content descriptors (e.g., "found-family", "slow-burn").
 */
@Dao
interface TagDao {
    // ========== Tag Entity Operations ==========

    /**
     * Get all tags ordered by book count (most popular first).
     *
     * @return Flow emitting list of all tags
     */
    @Query("SELECT * FROM tags ORDER BY bookCount DESC, slug ASC")
    fun observeAllTags(): Flow<List<TagEntity>>

    /**
     * Get all tags synchronously, ordered by book count.
     *
     * @return List of all tags
     */
    @Query("SELECT * FROM tags ORDER BY bookCount DESC, slug ASC")
    suspend fun getAllTags(): List<TagEntity>

    /**
     * Get a tag by ID.
     *
     * @param id The tag ID
     * @return The tag entity or null if not found
     */
    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    /**
     * Get a tag by slug.
     *
     * @param slug The tag slug
     * @return The tag entity or null if not found
     */
    @Query("SELECT * FROM tags WHERE slug = :slug")
    suspend fun getBySlug(slug: String): TagEntity?

    /**
     * Insert or update a tag entity.
     *
     * @param tag The tag entity to upsert
     */
    @Upsert
    suspend fun upsert(tag: TagEntity)

    /**
     * Insert or update multiple tag entities.
     *
     * @param tags List of tag entities to upsert
     */
    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    /**
     * Delete a tag by ID.
     *
     * @param id The tag ID to delete
     */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete all tags.
     * Used for testing and full re-sync scenarios.
     */
    @Query("DELETE FROM tags")
    suspend fun deleteAll()

    // ========== Book-Tag Relationship Operations ==========

    /**
     * Insert a book-tag relationship.
     *
     * @param crossRef The book-tag relationship to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookTag(crossRef: BookTagCrossRef)

    /**
     * Insert multiple book-tag relationships.
     *
     * @param crossRefs List of book-tag relationships to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllBookTags(crossRefs: List<BookTagCrossRef>)

    /**
     * Delete a book-tag relationship.
     *
     * @param bookId The book ID
     * @param tagId The tag ID
     */
    @Query("DELETE FROM book_tags WHERE bookId = :bookId AND tagId = :tagId")
    suspend fun deleteBookTag(
        bookId: BookId,
        tagId: String,
    )

    /**
     * Delete all tags for a book.
     * Used when syncing to replace all tags.
     *
     * @param bookId The book ID
     */
    @Query("DELETE FROM book_tags WHERE bookId = :bookId")
    suspend fun deleteTagsForBook(bookId: BookId)

    /**
     * Delete all tags for multiple books.
     * Used by sync to batch-delete before re-inserting.
     *
     * @param bookIds List of book IDs
     */
    @Query("DELETE FROM book_tags WHERE bookId IN (:bookIds)")
    suspend fun deleteTagsForBooks(bookIds: List<BookId>)

    /**
     * Get all tags for a book.
     *
     * @param bookId The book ID
     * @return List of tags for the book
     */
    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query(
        """
        SELECT * FROM tags
        INNER JOIN book_tags ON tags.id = book_tags.tagId
        WHERE book_tags.bookId = :bookId
        ORDER BY tags.slug ASC
    """,
    )
    suspend fun getTagsForBook(bookId: BookId): List<TagEntity>

    /**
     * Observe all tags for a book reactively.
     *
     * @param bookId The book ID
     * @return Flow emitting list of tags for the book
     */
    @RewriteQueriesToDropUnusedColumns
    @Transaction
    @Query(
        """
        SELECT * FROM tags
        INNER JOIN book_tags ON tags.id = book_tags.tagId
        WHERE book_tags.bookId = :bookId
        ORDER BY tags.slug ASC
    """,
    )
    fun observeTagsForBook(bookId: BookId): Flow<List<TagEntity>>

    /**
     * Delete all book-tag relationships.
     * Used for testing and full re-sync scenarios.
     */
    @Query("DELETE FROM book_tags")
    suspend fun deleteAllBookTags()

    // ========== Tag Detail Screen Queries ==========

    /**
     * Observe a tag by ID reactively.
     *
     * @param id The tag ID
     * @return Flow emitting the tag entity or null
     */
    @Query("SELECT * FROM tags WHERE id = :id")
    fun observeById(id: String): Flow<TagEntity?>

    /**
     * Observe all book IDs for a tag reactively.
     *
     * @param tagId The tag ID
     * @return Flow emitting list of book IDs
     */
    @Query("SELECT bookId FROM book_tags WHERE tagId = :tagId")
    fun observeBookIdsForTag(tagId: String): Flow<List<BookId>>

    /**
     * Get all book IDs for a tag.
     *
     * @param tagId The tag ID
     * @return List of book IDs
     */
    @Query("SELECT bookId FROM book_tags WHERE tagId = :tagId")
    suspend fun getBookIdsForTag(tagId: String): List<BookId>
}
