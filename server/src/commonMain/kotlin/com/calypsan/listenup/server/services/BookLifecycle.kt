package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.EntityRepository
import com.calypsan.listenup.server.sync.WorldEventRepository
import kotlin.time.Clock

private val log = loggerFor<BookLifecycle>()

/**
 * Absence sweeps, soft-delete cascades, folder queries, and tombstone revival for [BookRepository].
 *
 * Owns the symmetric removal/revival lifecycle across `books` and its cascade-tombstoned
 * junctions (`book_tags`, `book_moods`, `collection_books`, Story World `entities` and
 * `world_events`, plus [OrphanParentPurger]).
 */
internal class BookLifecycle(
    private val db: ListenUpDatabase,
    private val clock: Clock,
    private val sync: BookSyncBridge,
    private val locationManager: BookLocationManager,
    private val bookTagRepository: BookTagRepository?,
    private val bookMoodRepository: BookMoodRepository?,
    private val collectionBookRepository: CollectionBookRepository?,
    private val entityRepository: EntityRepository?,
    private val worldEventRepository: WorldEventRepository?,
    private val orphanParentPurger: OrphanParentPurger?,
) {
    /**
     * Tombstones this book and cascade-soft-deletes all of its junction rows (`book_tags`,
     * `book_moods`, and `collection_books`) and its book-homed Story World entities/events, then
     * purges any metadata parents left orphaned by the removal.
     */
    suspend fun softDelete(
        id: BookId,
        clientOpId: String?,
        userId: String?,
    ): AppResult<Unit> {
        val linkedParents = orphanParentPurger?.captureParents(id.value)
        val result = sync.softDeleteRow(id, clientOpId, userId)
        if (result is AppResult.Success) {
            bookTagRepository?.softDeleteAllForBook(id.value)
            bookMoodRepository?.softDeleteAllForBook(id.value)
            collectionBookRepository?.softDeleteAllForBook(id.value)
            entityRepository?.softDeleteAllForBook(id.value)
            worldEventRepository?.softDeleteAllForBook(id.value)
            if (linkedParents != null) orphanParentPurger.purgeOrphaned(linkedParents)
        }
        return result
    }

    /**
     * Revives the junction rows (`book_tags` / `book_moods` / `collection_books`) for [bookIds] that
     * were tombstoned at or after [cascadeFloor], and the book-homed Story World entities and the events
     * the book homes or anchors that the removal tombstoned (decided by their history, not the floor — see
     * [com.calypsan.listenup.server.sync.EntityRepository.reviveAllForBooks]) —
     * the same cascade [reviveByIds] runs for a folder re-add, reused by the scan revival paths.
     *
     * A scan re-ingest of a removed book revives the book ROW (`updateContent` clears `deleted_at`) but
     * would otherwise leave its cascade-tombstoned junctions dead — so the book returned uncollected
     * (invisible under the pure-union rule) with the user's tags/moods silently gone. [cascadeFloor] is
     * the book's own `deleted_at`, so only junctions tombstoned BY that removal return; a membership the
     * user removed manually earlier (an older tombstone) stays dead. A no-op when [bookIds] is empty;
     * each repo opens its own transaction (the per-row substrate contract), run after the reviving book
     * write commits.
     */
    suspend fun reviveBookJunctions(
        bookIds: List<String>,
        cascadeFloor: Long,
    ) {
        if (bookIds.isEmpty()) return
        bookTagRepository?.reviveAllForBooks(bookIds, cascadeFloor)
        bookMoodRepository?.reviveAllForBooks(bookIds, cascadeFloor)
        collectionBookRepository?.reviveAllForBooks(bookIds, cascadeFloor)
        entityRepository?.reviveAllForBooks(bookIds)
        // After the entities, so revived events recompute their mentions against revived entities.
        worldEventRepository?.reviveAllForBooks(bookIds)
    }

    /**
     * Tombstones every non-deleted book in [libraryId] that a completed FULL scan did not see.
     * Full-scan only.
     */
    suspend fun softDeleteAbsent(
        libraryId: LibraryId,
        seenIds: Set<BookId>,
    ) {
        val seenSet = seenIds.mapTo(mutableSetOf()) { it.value }
        val toDelete =
            suspendTransaction(db) {
                db.booksQueries
                    .selectLiveIdsAndPathsForLibrary(libraryId.value)
                    .executeAsList()
                    .map { it.id }
                    .filterNot { it in seenSet }
            }
        for (id in toDelete) {
            softDelete(BookId(id), clientOpId = null, userId = null)
        }
    }

    /**
     * Tombstones every non-deleted book in [libraryId] whose `(folderId, rootRelPath)` locator is
     * not in [seen], sparing any book that [BookLocationManager.movedAfterTheWalk] proves was moved
     * after the walk recorded it. Full-scan only.
     */
    suspend fun softDeleteAbsentByPaths(
        libraryId: LibraryId,
        seen: Set<FolderScopedPath>,
        walkedIdentityFiles: Map<FolderScopedInode, String>,
    ): Int {
        val absentFromWalk =
            suspendTransaction(db) {
                db.booksQueries
                    .selectLiveIdsAndPathsForLibrary(libraryId.value)
                    .executeAsList()
                    .filterNot { FolderScopedPath(FolderId(it.folder_id), it.root_rel_path) in seen }
            }
        val (movedSinceWalk, toDelete) =
            absentFromWalk.partition { row ->
                locationManager.movedAfterTheWalk(
                    bookId = row.id,
                    folderId = FolderId(row.folder_id),
                    rootRelPath = row.root_rel_path,
                    walkedIdentityFiles = walkedIdentityFiles,
                )
            }
        if (movedSinceWalk.isNotEmpty()) {
            log.info {
                "Sweep spared ${movedSinceWalk.size} book(s) moved after the walk recorded them at their old " +
                    "folder: ${movedSinceWalk.map { it.root_rel_path }}"
            }
        }
        for (id in toDelete.map { it.id }) {
            softDelete(BookId(id), clientOpId = null, userId = null)
        }
        return toDelete.size
    }

    /**
     * Soft-deletes the live book at `(folderId, rootRelPath)`, if one exists.
     * Idempotent: a no-op when no live book exists at that folder-scoped path.
     */
    suspend fun softDeleteByPath(
        folderId: FolderId,
        rootRelPath: String,
    ): Int {
        val id =
            suspendTransaction<BookId?>(db) {
                db.booksQueries
                    .selectLiveIdByPath(folderId.value, rootRelPath)
                    .executeAsOneOrNull()
                    ?.let { BookId(it) }
            } ?: return 0 // already gone — idempotent no-op
        softDelete(id, clientOpId = null, userId = null)
        return 1
    }

    /** All book ids under [folderId] (tombstone-inclusive). */
    suspend fun idsByFolder(folderId: FolderId): List<BookId> =
        suspendTransaction(db) {
            db.booksQueries
                .selectIdsByFolder(folderId.value)
                .executeAsList()
                .map { BookId(it) }
        }

    /** Ids of books under [folderId] tombstoned at or after [deletedAtFloor]. */
    suspend fun idsByFolderDeletedSince(
        folderId: FolderId,
        deletedAtFloor: Long,
    ): List<BookId> =
        suspendTransaction(db) {
            db.booksQueries
                .selectIdsByFolderDeletedSince(folder_id = folderId.value, deleted_at = deletedAtFloor)
                .executeAsList()
                .map { BookId(it) }
        }

    /**
     * Revives a tombstoned book: clears `deleted_at` and bumps the revision so clients reflow it
     * as live, emitting a [com.calypsan.listenup.api.sync.SyncEvent.Updated] after commit.
     */
    suspend fun reviveById(id: BookId): AppResult<Unit> {
        val idStr = id.value
        return suspendTransaction(db) {
            val rev = sync.nextRevision()
            val now = clock.now().toEpochMilliseconds()
            db.booksQueries.reviveById(revision = rev, updated_at = now, id = idStr)
            if (db.booksQueries.changes().executeAsOne() == 0L) {
                AppResult.Failure(SyncError.NotFound(domain = sync.domainName, entityId = idStr))
            } else {
                with(sync) { emitBookUpdatedAfterCommit(idStr = idStr, rev = rev, now = now) }
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * Batch-revives every book in [ids] (clears `deleted_at`, bumps a per-book revision, emits a
     * per-book [com.calypsan.listenup.api.sync.SyncEvent.Updated]) in ONE transaction, then cascades
     * revival to the books' junctions and Story World entities/events via [reviveBookJunctions].
     */
    suspend fun reviveByIds(
        ids: List<BookId>,
        cascadeFloor: Long,
    ) {
        if (ids.isEmpty()) return
        suspendTransaction<Unit>(db) {
            for (id in ids) {
                val idStr = id.value
                val rev = sync.nextRevision()
                val now = clock.now().toEpochMilliseconds()
                db.booksQueries.reviveById(revision = rev, updated_at = now, id = idStr)
                if (db.booksQueries.changes().executeAsOne() != 0L) {
                    with(sync) { emitBookUpdatedAfterCommit(idStr = idStr, rev = rev, now = now) }
                }
            }
        }
        // Symmetric with softDelete's tombstone cascade: restore each book's user tags, moods,
        // collection memberships and Story World entities (each its own transaction, exactly as the
        // tombstone cascade is a separate call after the book write), floored on the folder-removal
        // instant so a remove-then-rescan keeps a book's memberships instead of losing them.
        reviveBookJunctions(ids.map { it.value }, cascadeFloor)
    }
}
