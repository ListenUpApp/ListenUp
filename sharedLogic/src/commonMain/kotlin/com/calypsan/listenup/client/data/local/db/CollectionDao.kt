package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [CollectionEntity] operations.
 *
 * Provides both reactive (Flow-based) and one-shot queries for collections.
 * Collections are admin-only features that group books for organizational purposes.
 */
@Dao
interface CollectionDao {
    /**
     * Observe all collections as a reactive Flow, ordered by name.
     * Emits new list whenever any collection changes.
     *
     * @return Flow emitting list of all collections
     */
    @Query("SELECT * FROM collections ORDER BY name ASC")
    fun observeAll(): Flow<List<CollectionEntity>>

    /**
     * Get all collections synchronously, ordered by name.
     *
     * @return List of all collections
     */
    @Query("SELECT * FROM collections ORDER BY name ASC")
    suspend fun getAll(): List<CollectionEntity>

    /**
     * Get a single collection by ID.
     *
     * @param id The collection ID
     * @return The collection entity or null if not found
     */
    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: String): CollectionEntity?

    /**
     * Insert or update a collection entity.
     * If a collection with the same ID exists, it will be updated.
     *
     * @param collection The collection entity to upsert
     */
    @Upsert
    suspend fun upsert(collection: CollectionEntity)

    /**
     * Insert or update multiple collection entities in a single transaction.
     *
     * @param collections List of collection entities to upsert
     */
    @Upsert
    suspend fun upsertAll(collections: List<CollectionEntity>)

    /**
     * Delete a collection by ID.
     *
     * @param id The collection ID to delete
     */
    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Delete all collections.
     * Used for testing and full re-sync scenarios.
     */
    @Query("DELETE FROM collections")
    suspend fun deleteAll()
}
