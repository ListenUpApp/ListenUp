package com.calypsan.listenup.client.data.sync.conflict

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.EntityType
import com.calypsan.listenup.client.data.local.db.PendingOperationEntity
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.SyncState

/**
 * Result of a push conflict check.
 */
data class PushConflict(
    val operationId: String,
    val reason: String,
)

/**
 * Pull-side conflict — server has a newer version than the local unsynced changes for [bookId].
 *
 * Carried so [BookDao.markConflict] can record [serverTimestamp] alongside the conflict marker.
 */
data class BookConflict(
    val bookId: BookId,
    val serverTimestamp: Timestamp,
)

/**
 * Interface for conflict detection operations.
 *
 * Enables testing by allowing mock implementations.
 */
interface ConflictDetectorContract {
    /**
     * Detect conflicts where server has newer version than local unsynced changes.
     */
    suspend fun detectBookConflicts(serverBooks: List<BookEntity>): List<BookConflict>

    /**
     * Check if local changes should be preserved (local is newer than server).
     */
    suspend fun shouldPreserveLocalChanges(serverBook: BookEntity): Boolean

    /**
     * Check if a pending push operation conflicts with server state.
     *
     * Returns a PushConflict if the server has newer changes than when
     * the operation was created.
     */
    suspend fun checkPushConflict(operation: PendingOperationEntity): PushConflict?
}

/**
 * Detects conflicts between local and server data using timestamp comparison.
 *
 * Per offline-first-operations-design.md:
 * - If server timestamp > local edit timestamp -> conflict (server wins, mark for user review)
 * - If local edit timestamp >= server timestamp -> local wins (preserve local changes)
 */
class ConflictDetector(
    private val bookDao: BookDao,
    private val contributorDao: ContributorDao,
    private val seriesDao: SeriesDao,
) : ConflictDetectorContract {
    /**
     * Detect conflicts where server has newer version than local unsynced changes.
     *
     * @param serverBooks Books fetched from server
     * @return Conflicts found, each carrying the book id and the server's newer timestamp
     */
    override suspend fun detectBookConflicts(serverBooks: List<BookEntity>): List<BookConflict> =
        serverBooks.mapNotNull { serverBook ->
            bookDao
                .getById(serverBook.id)
                ?.takeIf { it.syncState == SyncState.NOT_SYNCED }
                ?.takeIf { serverBook.updatedAt > it.lastModified }
                ?.let { BookConflict(serverBook.id, serverBook.updatedAt) }
        }

    /**
     * Check if local changes should be preserved (local is newer than server).
     *
     * @param serverBook Book from server
     * @return true if local version should be kept, false if server should overwrite
     */
    override suspend fun shouldPreserveLocalChanges(serverBook: BookEntity): Boolean =
        bookDao
            .getById(serverBook.id)
            ?.takeIf { it.syncState == SyncState.NOT_SYNCED }
            ?.let { it.lastModified >= serverBook.updatedAt }
            ?: false

    /**
     * Check if a pending push operation conflicts with server state.
     *
     * Compares the operation's creation time against the entity's updatedAt
     * timestamp (which reflects the last known server state).
     *
     * @return PushConflict if server has been updated since operation was created
     */
    override suspend fun checkPushConflict(operation: PendingOperationEntity): PushConflict? {
        // Only entity operations can conflict
        if (operation.entityId == null || operation.entityType == null) {
            return null
        }

        val serverTimestamp =
            when (operation.entityType) {
                EntityType.BOOK -> {
                    bookDao.getById(BookId(operation.entityId))?.updatedAt?.epochMillis
                }

                EntityType.CONTRIBUTOR -> {
                    contributorDao.getById(operation.entityId)?.updatedAt?.epochMillis
                }

                EntityType.SERIES -> {
                    seriesDao.getById(operation.entityId)?.updatedAt?.epochMillis
                }

                EntityType.USER -> {
                    // User profile updates don't have server version tracking
                    // The server handles conflicts internally (last-write-wins)
                    null
                }

                EntityType.SHELF -> {
                    // Shelf updates use simple last-write-wins
                    null
                }
            } ?: return null // Entity not found - no conflict, but might fail on push

        // If server was updated after we queued our change, it's a conflict
        return if (serverTimestamp > operation.createdAt) {
            PushConflict(
                operationId = operation.id,
                reason = "Server has newer changes",
            )
        } else {
            null
        }
    }
}
