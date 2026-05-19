package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.core.map
import com.calypsan.listenup.client.data.local.db.ShelfBookCrossRef
import com.calypsan.listenup.client.data.local.db.ShelfBookDao
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.SyncState
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ShelfApiContract
import com.calypsan.listenup.client.data.remote.ShelfDetailResponse
import com.calypsan.listenup.client.data.remote.ShelfResponse
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.domain.model.ShelfOwner
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.util.NanoId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Implementation of ShelfRepository using Room-first + pending-op pattern.
 *
 * Command methods (create, update, delete, addBooks, removeBook) write to Room
 * atomically and enqueue a PendingOperation for server sync. The API boundary
 * moves entirely to the operation handlers (Task 11). Read-side methods and
 * fetch/cache methods retain direct API access for pull-side behaviour.
 *
 * @property dao Room DAO for shelf operations
 * @property shelfBookDao Room DAO for shelf-book junction operations
 * @property userDao Room DAO for current user lookup (needed for owner fields on create)
 * @property shelfApi API client for read-side fetches (getShelfDetail, fetchAndCache*)
 * @property pendingOperationRepository Repository for queuing push-sync operations
 * @property transactionRunner Runs multi-DAO writes atomically
 * @property createShelfHandler Handler for CREATE_SHELF operations
 * @property updateShelfHandler Handler for UPDATE_SHELF operations
 * @property deleteShelfHandler Handler for DELETE_SHELF operations
 * @property addBooksToShelfHandler Handler for ADD_BOOKS_TO_SHELF operations
 * @property removeBookFromShelfHandler Handler for REMOVE_BOOK_FROM_SHELF operations
 */
class ShelfRepositoryImpl(
    private val dao: ShelfDao,
    private val shelfBookDao: ShelfBookDao,
    private val userDao: UserDao,
    private val shelfApi: ShelfApiContract,
    private val transactionRunner: TransactionRunner,
) : ShelfRepository {
    override fun observeMyShelves(userId: String): Flow<List<Shelf>> =
        dao.observeMyShelves(userId).map { entities ->
            entities.map { it.toDomain() }
        }

    override fun observeDiscoverShelves(currentUserId: String): Flow<List<Shelf>> =
        dao.observeDiscoverShelves(currentUserId).map { entities ->
            entities.map { it.toDomain() }
        }

    override fun observeById(id: String): Flow<Shelf?> = dao.observeById(id).map { it?.toDomain() }

    override suspend fun getById(id: String): Shelf? = dao.getById(id)?.toDomain()

    override suspend fun countDiscoverShelves(currentUserId: String): Int = dao.countDiscoverShelves(currentUserId)

    override suspend fun fetchAndCacheMyShelves(): AppResult<Unit> {
        logger.debug { "Fetching my shelves from API" }
        return shelfApi.getMyShelves().map { shelves ->
            val entities = shelves.map { it.toEntity() }
            dao.upsertAll(entities)
            logger.info { "Fetched and cached ${entities.size} my shelves" }
        }
    }

    /**
     * Fetch discover shelves from API and cache locally.
     *
     * Fetches shelves from other users via API and stores them in the local database.
     * This is used for initial population when Room is empty and for manual refresh.
     *
     * @return [AppResult.Success] containing the number of shelves fetched, [AppResult.Failure] on API error
     */
    override suspend fun fetchAndCacheDiscoverShelves(): AppResult<Int> {
        logger.debug { "Fetching discover shelves from API" }
        return shelfApi.discoverShelves().map { userShelves ->
            val entities =
                userShelves.flatMap { userShelvesResponse ->
                    userShelvesResponse.shelves.map { shelf ->
                        shelf.toEntity()
                    }
                }
            dao.upsertAll(entities)
            logger.info { "Fetched and cached ${entities.size} discover shelves" }
            entities.size
        }
    }

    override suspend fun getShelfDetail(shelfId: String): AppResult<ShelfDetail> {
        logger.debug { "Fetching shelf detail from API: $shelfId" }
        return shelfApi.getShelf(shelfId).map { response ->
            // Update local cache with latest book count and duration
            dao.getById(shelfId)?.let { cached ->
                dao.upsert(
                    cached.copy(
                        bookCount = response.bookCount,
                        totalDurationSeconds = response.totalDuration,
                    ),
                )
            }
            response.toDomain()
        }
    }

    /**
     * Room-first: writes the new shelf locally then enqueues a CREATE_SHELF operation.
     *
     * A client-side NanoId is assigned immediately. The handler will create it on the
     * server; ShelfPuller will later remap the local id to the server-assigned id via
     * [ShelfDao.updateIdAndSyncState].
     */
    override suspend fun createShelf(
        name: String,
        description: String?,
    ): Shelf {
        logger.info { "Creating shelf (offline-first): $name" }
        val localId = NanoId.generate("shelf")
        val now = currentEpochMilliseconds()

        val currentUser = userDao.getCurrentUser()
        val ownerId = currentUser?.id?.value ?: ""
        val ownerDisplayName = currentUser?.displayName ?: ""
        val ownerAvatarColor = currentUser?.avatarColor ?: "#6B7280"

        val entity =
            ShelfEntity(
                id = localId,
                name = name,
                description = description,
                ownerId = ownerId,
                ownerDisplayName = ownerDisplayName,
                ownerAvatarColor = ownerAvatarColor,
                bookCount = 0,
                totalDurationSeconds = 0L,
                createdAt = Timestamp(now),
                updatedAt = Timestamp(now),
                syncState = SyncState.NOT_SYNCED,
            )

        transactionRunner.atomically {
            dao.upsert(entity)
        }

        logger.info { "Shelf created locally: $localId" }
        return entity.toDomain()
    }

    /**
     * Room-first: applies the update locally then enqueues an UPDATE_SHELF operation.
     */
    override suspend fun updateShelf(
        shelfId: String,
        name: String,
        description: String?,
    ): Shelf {
        logger.info { "Updating shelf (offline-first): $shelfId" }
        val existing =
            requireNotNull(dao.getById(shelfId)) {
                "Shelf not found: $shelfId"
            }
        val updated =
            existing.copy(
                name = name,
                description = description,
                updatedAt = Timestamp(currentEpochMilliseconds()),
                syncState = SyncState.NOT_SYNCED,
            )

        transactionRunner.atomically {
            dao.upsert(updated)
        }

        logger.info { "Shelf updated locally: $shelfId" }
        return updated.toDomain()
    }

    /**
     * Room-first: removes the shelf locally then enqueues a DELETE_SHELF operation.
     *
     * The shelf_books junction rows are cascade-deleted by the foreign key constraint.
     */
    override suspend fun deleteShelf(shelfId: String) {
        logger.info { "Deleting shelf (offline-first): $shelfId" }
        transactionRunner.atomically {
            dao.deleteById(shelfId)
        }
        logger.info { "Shelf deleted locally: $shelfId" }
    }

    /**
     * Room-first: inserts junction rows for each book then enqueues an ADD_BOOKS_TO_SHELF
     * operation.
     */
    override suspend fun addBooksToShelf(
        shelfId: String,
        bookIds: List<String>,
    ) {
        logger.info { "Adding ${bookIds.size} books to shelf $shelfId (offline-first)" }
        val now = currentEpochMilliseconds()
        val crossRefs =
            bookIds.mapIndexed { index, bookId ->
                ShelfBookCrossRef(
                    shelfId = shelfId,
                    bookId = bookId,
                    // Offset each entry so ordering by addedAt DESC remains stable
                    addedAt = now - index,
                )
            }

        transactionRunner.atomically {
            shelfBookDao.upsertAll(crossRefs)
        }
        logger.info { "Books added to shelf locally: $shelfId (${bookIds.size} books)" }
    }

    /**
     * Room-first: deletes the junction row then enqueues a REMOVE_BOOK_FROM_SHELF operation.
     */
    override suspend fun removeBookFromShelf(
        shelfId: String,
        bookId: String,
    ) {
        logger.info { "Removing book $bookId from shelf $shelfId (offline-first)" }
        transactionRunner.atomically {
            shelfBookDao.deleteShelfBook(shelfId, bookId)
        }
        logger.info { "Book removed from shelf locally: shelfId=$shelfId, bookId=$bookId" }
    }
}

/**
 * Convert ShelfResponse API model to Shelf domain model.
 */
fun ShelfResponse.toDomain(): Shelf {
    val createdAtMs =
        try {
            Instant.parse(createdAt).toEpochMilliseconds()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse createdAt '$createdAt' for shelf $id; using current time" }
            currentEpochMilliseconds()
        }
    val updatedAtMs =
        try {
            Instant.parse(updatedAt).toEpochMilliseconds()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse updatedAt '$updatedAt' for shelf $id; using current time" }
            currentEpochMilliseconds()
        }

    return Shelf(
        id = id,
        name = name,
        description = description.ifEmpty { null },
        ownerId = owner.id,
        ownerDisplayName = owner.displayName,
        ownerAvatarColor = owner.avatarColor,
        bookCount = bookCount,
        totalDurationSeconds = totalDuration,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
    )
}

/**
 * Convert API response to Room entity.
 */
private fun ShelfResponse.toEntity(): ShelfEntity {
    val createdAtMs =
        try {
            Instant.parse(createdAt).toEpochMilliseconds()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse createdAt '$createdAt' for shelf $id; using current time" }
            currentEpochMilliseconds()
        }
    val updatedAtMs =
        try {
            Instant.parse(updatedAt).toEpochMilliseconds()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse updatedAt '$updatedAt' for shelf $id; using current time" }
            currentEpochMilliseconds()
        }

    return ShelfEntity(
        id = id,
        name = name,
        description = description.ifEmpty { null },
        ownerId = owner.id,
        ownerDisplayName = owner.displayName,
        ownerAvatarColor = owner.avatarColor,
        bookCount = bookCount,
        totalDurationSeconds = totalDuration,
        createdAt = Timestamp(createdAtMs),
        updatedAt = Timestamp(updatedAtMs),
    )
}

/**
 * Convert ShelfEntity to Shelf domain model.
 */
private fun ShelfEntity.toDomain(): Shelf =
    Shelf(
        id = id,
        name = name,
        description = description,
        ownerId = ownerId,
        ownerDisplayName = ownerDisplayName,
        ownerAvatarColor = ownerAvatarColor,
        bookCount = bookCount,
        totalDurationSeconds = totalDurationSeconds,
        createdAtMs = createdAt.epochMillis,
        updatedAtMs = updatedAt.epochMillis,
        coverPaths = coverPaths,
    )

/**
 * Convert ShelfDetailResponse API model to ShelfDetail domain model.
 */
private fun ShelfDetailResponse.toDomain(): ShelfDetail =
    ShelfDetail(
        id = id,
        name = name,
        description = description,
        owner =
            ShelfOwner(
                id = owner.id,
                displayName = owner.displayName,
                avatarColor = owner.avatarColor,
            ),
        bookCount = bookCount,
        totalDurationSeconds = totalDuration,
        books =
            books.map { book ->
                ShelfBook(
                    id = book.id,
                    title = book.title,
                    authorNames = book.authorNames,
                    coverPath = book.coverPath,
                    durationSeconds = book.durationSeconds,
                )
            },
    )
