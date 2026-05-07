package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.model.Genre
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for genre operations.
 *
 * Provides access to system-defined hierarchical categories.
 * Genres form a tree structure (e.g., Fiction > Fantasy > Epic Fantasy).
 *
 * Part of the domain layer - implementations live in the data layer.
 *
 * Read methods (`observeAll`, `getAll`, `getById`, `getBySlug`,
 * `observeGenresForBook`, `getGenresForBook`, `getBookIdsForGenre`)
 * are local-only and cannot fail — they return plain values.
 *
 * Write methods that touch the server (`setGenresForBook`, `createGenre`,
 * `updateGenre`, `deleteGenre`, `moveGenre`) return [AppResult] — success
 * carries the value, failure carries a typed
 * [com.calypsan.listenup.api.error.AppError].
 */
interface GenreRepository {
    /**
     * Observe all genres reactively, ordered hierarchically.
     *
     * @return Flow emitting list of all genres
     */
    fun observeAll(): Flow<List<Genre>>

    /**
     * Get all genres synchronously.
     *
     * @return List of all genres
     */
    suspend fun getAll(): List<Genre>

    /**
     * Get a genre by ID.
     *
     * @param id The genre ID
     * @return Genre if found, null otherwise
     */
    suspend fun getById(id: String): Genre?

    /**
     * Get a genre by slug.
     *
     * @param slug The genre slug (e.g., "epic-fantasy")
     * @return Genre if found, null otherwise
     */
    suspend fun getBySlug(slug: String): Genre?

    /**
     * Observe genres for a specific book.
     *
     * @param bookId The book ID
     * @return Flow emitting list of genres for the book
     */
    fun observeGenresForBook(bookId: String): Flow<List<Genre>>

    /**
     * Get genres for a specific book synchronously.
     *
     * @param bookId The book ID
     * @return List of genres for the book
     */
    suspend fun getGenresForBook(bookId: String): List<Genre>

    /**
     * Get all book IDs that have a specific genre.
     *
     * @param genreId The genre ID
     * @return List of book IDs with this genre
     */
    suspend fun getBookIdsForGenre(genreId: String): List<String>

    /**
     * Set genres for a book (replaces all existing genres).
     *
     * Calls API to update server; on success, updates local Room for reactivity.
     * On failure, the local cache is left untouched.
     *
     * @param bookId The book ID
     * @param genreIds List of genre IDs to set
     */
    suspend fun setGenresForBook(
        bookId: String,
        genreIds: List<String>,
    ): AppResult<Unit>

    /**
     * Create a new genre.
     */
    suspend fun createGenre(
        name: String,
        parentId: String?,
    ): AppResult<Genre>

    /**
     * Update an existing genre's name.
     */
    suspend fun updateGenre(
        id: String,
        name: String,
    ): AppResult<Genre>

    /**
     * Delete a genre.
     *
     * Calls API to delete from server; on success, removes the genre from
     * local Room for immediate reactivity. On failure, the local cache is
     * left untouched.
     */
    suspend fun deleteGenre(id: String): AppResult<Unit>

    /**
     * Move a genre to a new parent.
     */
    suspend fun moveGenre(
        id: String,
        newParentId: String?,
    ): AppResult<Unit>
}
