package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.model.Collection
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for collection operations.
 *
 * Provides access to admin-managed book collections.
 * Collections are organizational groups created by administrators.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface CollectionRepository {
    /**
     * Observe all collections reactively, ordered by name.
     *
     * @return Flow emitting list of all collections
     */
    fun observeAll(): Flow<List<Collection>>

    /**
     * Get all collections synchronously.
     *
     * @return List of all collections
     */
    suspend fun getAll(): List<Collection>

    /**
     * Get a collection by ID.
     *
     * @param id The collection ID
     * @return Collection if found, null otherwise
     */
    suspend fun getById(id: String): Collection?

    /**
     * Insert or update a collection.
     *
     * Used during sync operations to persist server data locally.
     *
     * @param collection The collection to save
     */
    suspend fun upsert(collection: Collection)

    /**
     * Delete a collection by ID from local storage.
     *
     * @param id The collection ID to delete
     */
    suspend fun deleteById(id: String)

    /**
     * Create a new collection on server and persist locally.
     *
     * @param name The collection name
     * @return [AppResult.Success] containing the created collection, [AppResult.Failure] on API error
     */
    suspend fun create(name: String): AppResult<Collection>

    /**
     * Delete a collection on server and remove from local storage.
     *
     * @param id The collection ID to delete
     * @return [AppResult.Success] on deletion, [AppResult.Failure] on API error
     */
    suspend fun delete(id: String): AppResult<Unit>

    /**
     * Add books to a collection via the server API.
     *
     * @param collectionId The collection to add books to
     * @param bookIds The book IDs to add
     * @return [AppResult.Success] on success, [AppResult.Failure] on API error
     */
    suspend fun addBooksToCollection(
        collectionId: String,
        bookIds: List<String>,
    ): AppResult<Unit>

    /**
     * Refresh collections from the server.
     *
     * Fetches latest collections from server and syncs with local database.
     * Adds new collections, updates existing ones, and removes deleted ones.
     *
     * @return [AppResult.Success] when refresh succeeds, [AppResult.Failure] on API error
     */
    suspend fun refreshFromServer(): AppResult<Unit>

    /**
     * Get collection details from the server.
     *
     * @param collectionId The collection ID
     * @return [AppResult.Success] containing the collection, [AppResult.Failure] on API error
     */
    suspend fun getCollectionFromServer(collectionId: String): AppResult<Collection>

    /**
     * Get books in a collection.
     *
     * @param collectionId The collection ID
     * @return [AppResult.Success] containing the list of book summaries, [AppResult.Failure] on API error
     */
    suspend fun getCollectionBooks(collectionId: String): AppResult<List<CollectionBookSummary>>

    /**
     * Update a collection's name.
     *
     * @param collectionId The collection ID
     * @param name The new name
     * @return [AppResult.Success] containing the updated collection, [AppResult.Failure] on API error
     */
    suspend fun updateCollectionName(
        collectionId: String,
        name: String,
    ): AppResult<Collection>

    /**
     * Remove a book from a collection.
     *
     * @param collectionId The collection ID
     * @param bookId The book ID to remove
     * @return [AppResult.Success] on removal, [AppResult.Failure] on API error
     */
    suspend fun removeBookFromCollection(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit>

    /**
     * Get shares for a collection.
     *
     * @param collectionId The collection ID
     * @return [AppResult.Success] containing the list of share summaries, [AppResult.Failure] on API error
     */
    suspend fun getCollectionShares(collectionId: String): AppResult<List<CollectionShareSummary>>

    /**
     * Share a collection with a user.
     *
     * @param collectionId The collection ID
     * @param userId The user to share with
     * @return [AppResult.Success] containing the created share summary, [AppResult.Failure] on API error
     */
    suspend fun shareCollection(
        collectionId: String,
        userId: String,
    ): AppResult<CollectionShareSummary>

    /**
     * Remove a share (unshare).
     *
     * @param shareId The share ID to remove
     * @return [AppResult.Success] on removal, [AppResult.Failure] on API error
     */
    suspend fun removeShare(shareId: String): AppResult<Unit>
}

/**
 * Summary of a book in a collection.
 */
data class CollectionBookSummary(
    val id: String,
    val title: String,
    val coverPath: String?,
)

/**
 * Summary of a collection share.
 *
 * userName and userEmail may be empty when fetched from the API,
 * as they require a separate user lookup. Use cases can enrich
 * this data with user information from UserRepository or AdminRepository.
 */
data class CollectionShareSummary(
    val id: String,
    val userId: String,
    val userName: String = "",
    val userEmail: String = "",
    val permission: String,
)
