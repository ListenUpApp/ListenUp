@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.model.CollectionShare
import com.calypsan.listenup.api.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for collection operations.
 *
 * Offline-first dispatcher over the [com.calypsan.listenup.api.CollectionService]
 * RPC surface: **reads** observe the local Room mirror (populated by the collection
 * sync handlers via firehose/catch-up), and **writes** dispatch to RPC. There are no
 * optimistic Room writes — the firehose echo is the single write path back into Room
 * (the Tags/Genres rule).
 *
 * Implementations live in the data layer.
 */
interface CollectionRepository {
    /** Observe all accessible collections reactively, ordered by name, with JOIN-derived book counts. */
    fun observeCollections(): Flow<List<Collection>>

    /** Observe the live book ids that are members of [collectionId]. */
    fun observeCollectionBooks(collectionId: String): Flow<List<String>>

    /** Observe the live collection ids that [bookId] currently belongs to. */
    fun observeBookCollectionIds(bookId: String): Flow<List<String>>

    /** Observe the active share grants on [collectionId]. */
    fun observeShares(collectionId: String): Flow<List<CollectionShare>>

    /** Create a new collection named [name] in [libraryId]. */
    suspend fun create(
        libraryId: String,
        name: String,
    ): AppResult<Collection>

    /** Rename the collection identified by [id] to [name]. */
    suspend fun rename(
        id: String,
        name: String,
    ): AppResult<Collection>

    /** Delete the collection identified by [id]. */
    suspend fun delete(id: String): AppResult<Unit>

    /** Add [bookId] to the collection identified by [collectionId]. */
    suspend fun addBook(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit>

    /** Remove [bookId] from the collection identified by [collectionId]. */
    suspend fun removeBook(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit>

    /** Share [collectionId] with [sharedWithUserId] at the given [permission] level. */
    suspend fun share(
        collectionId: String,
        sharedWithUserId: String,
        permission: SharePermission,
    ): AppResult<CollectionShare>

    /** Revoke the share on [collectionId] for [sharedWithUserId]. */
    suspend fun revokeShare(
        collectionId: String,
        sharedWithUserId: String,
    ): AppResult<Unit>
}
