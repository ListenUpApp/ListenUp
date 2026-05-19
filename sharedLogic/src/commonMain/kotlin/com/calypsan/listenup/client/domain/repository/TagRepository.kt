package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.Tag
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for tag operations.
 *
 * Provides access to community-wide content descriptors (tags).
 * Tags are user-applied labels like "found-family" or "slow-burn".
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface TagRepository {
    /**
     * Observe all tags reactively, ordered by popularity.
     *
     * @return Flow emitting list of all tags
     */
    fun observeAll(): Flow<List<Tag>>

    /**
     * Get all tags synchronously.
     *
     * @return List of all tags
     */
    suspend fun getAll(): List<Tag>

    /**
     * Get a tag by ID.
     *
     * @param id The tag ID
     * @return Tag if found, null otherwise
     */
    suspend fun getById(id: String): Tag?

    /**
     * Observe a tag by ID reactively.
     *
     * @param id The tag ID
     * @return Flow emitting the tag or null
     */
    fun observeById(id: String): Flow<Tag?>

    /**
     * Get a tag by slug.
     *
     * @param slug The tag slug (e.g., "found-family")
     * @return Tag if found, null otherwise
     */
    suspend fun getBySlug(slug: String): Tag?

    /**
     * Observe tags for a specific book.
     *
     * @param bookId The book ID
     * @return Flow emitting list of tags for the book
     */
    fun observeTagsForBook(bookId: String): Flow<List<Tag>>

    /**
     * Get tags for a specific book synchronously.
     *
     * @param bookId The book ID
     * @return List of tags for the book
     */
    suspend fun getTagsForBook(bookId: String): List<Tag>

    /**
     * Get all book IDs that have a specific tag.
     *
     * @param tagId The tag ID
     * @return List of book IDs with this tag
     */
    suspend fun getBookIdsForTag(tagId: String): List<String>

    /**
     * Observe all book IDs that have a specific tag reactively.
     *
     * @param tagId The tag ID
     * @return Flow emitting list of book IDs with this tag
     */
    fun observeBookIdsForTag(tagId: String): Flow<List<String>>

    /**
     * Add a tag to a book.
     *
     * Calls API to add the tag, then updates local Room for reactivity.
     * If the tag doesn't exist, it will be created by the server.
     *
     * @param bookId The book to add the tag to
     * @param tagSlugOrName The tag slug or name (will be normalized by server)
     * @return [AppResult.Success] containing the added tag, or [AppResult.Failure] on error
     */
    suspend fun addTagToBook(
        bookId: String,
        tagSlugOrName: String,
    ): AppResult<Tag>

    /**
     * Remove a tag from a book.
     *
     * Calls API to remove the tag, then updates local Room for reactivity.
     *
     * @param bookId The book to remove the tag from
     * @param tagSlug The tag slug to remove
     * @param tagId The tag ID (for local database update)
     * @return [AppResult.Success] with Unit on success, or [AppResult.Failure] on error
     */
    suspend fun removeTagFromBook(
        bookId: String,
        tagSlug: String,
        tagId: String,
    ): AppResult<Unit>
}
