package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.map
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.remote.AdminCollectionApiContract
import com.calypsan.listenup.client.data.remote.model.toTimestamp
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionBookSummary
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.CollectionShareSummary
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * Implementation of CollectionRepository using Room and AdminCollectionApi.
 *
 * Wraps CollectionDao for local operations and AdminCollectionApi for server operations.
 * Converts between entities and domain models.
 *
 * @property dao Room DAO for collection operations
 * @property adminCollectionApi API client for server collection operations
 */
class CollectionRepositoryImpl(
    private val dao: CollectionDao,
    private val adminCollectionApi: AdminCollectionApiContract,
) : CollectionRepository {
    override fun observeAll(): Flow<List<Collection>> =
        dao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getAll(): List<Collection> = dao.getAll().map { it.toDomain() }

    override suspend fun getById(id: String): Collection? = dao.getById(id)?.toDomain()

    override suspend fun upsert(collection: Collection) {
        dao.upsert(collection.toEntity())
    }

    override suspend fun deleteById(id: String) {
        dao.deleteById(id)
    }

    override suspend fun create(name: String): AppResult<Collection> {
        logger.info { "Creating collection: $name" }
        return adminCollectionApi.createCollection(name).map { response ->
            val collection =
                Collection(
                    id = response.id,
                    name = response.name,
                    bookCount = response.bookCount,
                    createdAtMs = response.createdAt.toTimestamp().epochMillis,
                    updatedAtMs = response.updatedAt.toTimestamp().epochMillis,
                )
            // Persist locally for immediate UI feedback
            dao.upsert(collection.toEntity())
            logger.info { "Created collection: ${collection.name} (${collection.id})" }
            collection
        }
    }

    override suspend fun delete(id: String): AppResult<Unit> {
        logger.info { "Deleting collection: $id" }
        return adminCollectionApi.deleteCollection(id).map {
            // Remove locally for immediate feedback
            dao.deleteById(id)
            logger.info { "Deleted collection: $id" }
        }
    }

    override suspend fun addBooksToCollection(
        collectionId: String,
        bookIds: List<String>,
    ): AppResult<Unit> {
        logger.info { "Adding ${bookIds.size} books to collection $collectionId" }
        return adminCollectionApi.addBooks(collectionId, bookIds)
    }

    override suspend fun refreshFromServer(): AppResult<Unit> {
        logger.debug { "Refreshing collections from server" }
        return adminCollectionApi.getCollections().map { serverCollections ->
            logger.debug { "Fetched ${serverCollections.size} collections from server" }

            // Update local database with server data
            serverCollections.forEach { response ->
                val collection =
                    Collection(
                        id = response.id,
                        name = response.name,
                        bookCount = response.bookCount,
                        createdAtMs = response.createdAt.toTimestamp().epochMillis,
                        updatedAtMs = response.updatedAt.toTimestamp().epochMillis,
                    )
                dao.upsert(collection.toEntity())
            }

            // Delete local collections that no longer exist on server
            val serverIds = serverCollections.map { it.id }.toSet()
            val localCollections = dao.getAll()
            localCollections.filter { it.id !in serverIds }.forEach { orphan ->
                logger.debug { "Removing orphaned collection: ${orphan.name} (${orphan.id})" }
                dao.deleteById(orphan.id)
            }
        }
    }

    override suspend fun getCollectionFromServer(collectionId: String): AppResult<Collection> {
        logger.debug { "Fetching collection from server: $collectionId" }
        return adminCollectionApi.getCollection(collectionId).map { response ->
            Collection(
                id = response.id,
                name = response.name,
                bookCount = response.bookCount,
                createdAtMs = response.createdAt.toTimestamp().epochMillis,
                updatedAtMs = response.updatedAt.toTimestamp().epochMillis,
            )
        }
    }

    override suspend fun getCollectionBooks(collectionId: String): AppResult<List<CollectionBookSummary>> {
        logger.debug { "Fetching books for collection: $collectionId" }
        return adminCollectionApi.getCollectionBooks(collectionId).map { books ->
            books.map { book ->
                CollectionBookSummary(
                    id = book.id,
                    title = book.title,
                    coverPath = book.coverPath,
                )
            }
        }
    }

    override suspend fun updateCollectionName(
        collectionId: String,
        name: String,
    ): AppResult<Collection> {
        logger.info { "Updating collection name: $collectionId -> $name" }
        return adminCollectionApi.updateCollection(collectionId, name).map { response ->
            Collection(
                id = response.id,
                name = response.name,
                bookCount = response.bookCount,
                createdAtMs = response.createdAt.toTimestamp().epochMillis,
                updatedAtMs = response.updatedAt.toTimestamp().epochMillis,
            )
        }
    }

    override suspend fun removeBookFromCollection(
        collectionId: String,
        bookId: String,
    ): AppResult<Unit> {
        logger.info { "Removing book $bookId from collection $collectionId" }
        return adminCollectionApi.removeBook(collectionId, bookId)
    }

    override suspend fun getCollectionShares(collectionId: String): AppResult<List<CollectionShareSummary>> {
        logger.debug { "Fetching shares for collection: $collectionId" }
        return adminCollectionApi.getCollectionShares(collectionId).map { shares ->
            shares.map { share ->
                CollectionShareSummary(
                    id = share.id,
                    userId = share.sharedWithUserId,
                    // userName and userEmail left as defaults - can be enriched by use case
                    permission = share.permission,
                )
            }
        }
    }

    override suspend fun shareCollection(
        collectionId: String,
        userId: String,
    ): AppResult<CollectionShareSummary> {
        logger.info { "Sharing collection $collectionId with user $userId" }
        return adminCollectionApi.shareCollection(collectionId, userId).map { share ->
            CollectionShareSummary(
                id = share.id,
                userId = share.sharedWithUserId,
                // userName and userEmail left as defaults - can be enriched by use case
                permission = share.permission,
            )
        }
    }

    override suspend fun removeShare(shareId: String): AppResult<Unit> {
        logger.info { "Removing share: $shareId" }
        return adminCollectionApi.deleteShare(shareId)
    }
}

/**
 * Convert CollectionEntity to Collection domain model.
 */
private fun CollectionEntity.toDomain(): Collection =
    Collection(
        id = id,
        name = name,
        bookCount = bookCount,
        createdAtMs = createdAt.epochMillis,
        updatedAtMs = updatedAt.epochMillis,
    )

/**
 * Convert Collection domain model to CollectionEntity.
 */
private fun Collection.toEntity(): CollectionEntity =
    CollectionEntity(
        id = id,
        name = name,
        bookCount = bookCount,
        createdAt = Timestamp(createdAtMs),
        updatedAt = Timestamp(updatedAtMs),
    )
