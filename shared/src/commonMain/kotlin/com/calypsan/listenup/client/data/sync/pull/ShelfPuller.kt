package com.calypsan.listenup.client.data.sync.pull

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.ShelfBookCrossRef
import com.calypsan.listenup.client.data.local.db.ShelfBookDao
import com.calypsan.listenup.client.data.local.db.ShelfDao
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.ShelfApiContract
import com.calypsan.listenup.client.data.remote.ShelfResponse
import com.calypsan.listenup.client.data.sync.model.SyncStatus
import com.calypsan.listenup.client.domain.repository.ImageStorage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Syncs the current user's shelves from server.
 *
 * Fetches all shelves owned by the authenticated user and caches them
 * in Room for offline access. Also fetches individual shelf details to
 * populate the shelf-book relationships for offline-first shelf content.
 *
 * Non-critical — failures are logged but don't block the rest of the sync.
 */
class ShelfPuller(
    private val transactionRunner: TransactionRunner,
    private val shelfApi: ShelfApiContract,
    private val shelfDao: ShelfDao,
    private val shelfBookDao: ShelfBookDao,
    private val imageStorage: ImageStorage,
) : Puller {
    override suspend fun pull(
        updatedAfter: String?,
        onProgress: (SyncStatus) -> Unit,
    ): AppResult<Unit> {
        logger.debug { "Starting shelf sync..." }

        // 1. Fetch basic shelf list
        val shelvesResult = shelfApi.getMyShelves()
        val shelves =
            when (shelvesResult) {
                is AppResult.Success -> {
                    shelvesResult.data
                }

                is AppResult.Failure -> {
                    logger.warn { "Failed to fetch shelves: ${shelvesResult.error.message}" }
                    // Non-critical — don't throw
                    return AppResult.Success(Unit)
                }
            }

        logger.info { "Fetched ${shelves.size} shelves" }

        // 2. Fetch individual shelf details for book relationships
        val allShelfBooks = mutableListOf<ShelfBookCrossRef>()
        val entitiesWithCovers = mutableListOf<ShelfEntity>()

        shelves.forEach { shelfResponse ->
            when (val detailResult = shelfApi.getShelf(shelfResponse.id)) {
                is AppResult.Success -> {
                    val shelfDetail = detailResult.data
                    logger.debug {
                        "Fetched details for shelf '${shelfDetail.name}' with ${shelfDetail.books.size} books"
                    }

                    // Create shelf-book relationships
                    val shelfBooks =
                        shelfDetail.books.mapIndexed { index, book ->
                            ShelfBookCrossRef(
                                shelfId = shelfDetail.id,
                                bookId = book.id,
                                // Use reverse order to maintain newest-first when ordering by addedAt DESC
                                addedAt = currentEpochMilliseconds() - index,
                            )
                        }

                    // Resolve cover paths from LOCAL image storage (not server paths)
                    val coverPaths =
                        shelfDetail.books
                            .map { BookId(it.id) }
                            .filter { imageStorage.exists(it) }
                            .take(4)
                            .map { imageStorage.getCoverPath(it) }

                    logger.debug {
                        "Shelf '${shelfDetail.name}': resolved ${coverPaths.size} local cover paths from ${shelfDetail.books.size} books"
                    }

                    // Create entity with locally-resolved cover paths
                    val entity = shelfResponse.toEntity().copy(coverPaths = coverPaths)
                    entitiesWithCovers.add(entity)

                    allShelfBooks.addAll(shelfBooks)
                }

                is AppResult.Failure -> {
                    logger.warn {
                        "Failed to fetch details for shelf ${shelfResponse.id}: ${detailResult.error.message}, using basic info"
                    }
                    // Fall back to basic entity without cover paths
                    entitiesWithCovers.add(shelfResponse.toEntity())
                }
            }
        }

        // 3. Commit the refreshed shelves and shelf-book cross-refs in a single
        //    transaction so the pull never leaves the DB with shelves that
        //    reference books we haven't yet re-linked (Finding 05 D2).
        try {
            transactionRunner.atomically {
                shelfDao.upsertAll(entitiesWithCovers)
                shelfBookDao.deleteAll()
                shelfBookDao.upsertAll(allShelfBooks)
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to persist shelf data to Room" }
            // Non-critical — don't throw
            return AppResult.Success(Unit)
        }

        logger.info {
            "Shelf sync complete: ${entitiesWithCovers.size} shelves, ${allShelfBooks.size} shelf-book relationships cached"
        }

        return AppResult.Success(Unit)
    }
}

private fun ShelfResponse.toEntity(): ShelfEntity {
    val createdAtMs =
        try {
            Instant.parse(createdAt).toEpochMilliseconds()
        } catch (_: Exception) {
            currentEpochMilliseconds()
        }
    val updatedAtMs =
        try {
            Instant.parse(updatedAt).toEpochMilliseconds()
        } catch (_: Exception) {
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
        coverPaths = emptyList(),
    )
}
