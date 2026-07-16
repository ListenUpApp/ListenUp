package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.Library_folders
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlFragment
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for library folders — a **global (cross-user)** aggregate.
 * Each folder belongs to a parent library (`library_id`); the FK
 * `library_id REFERENCES libraries(id) ON DELETE CASCADE` (in the migration DDL) is the
 * hard-delete safety net beneath the app-layer soft-delete cascade in
 * [com.calypsan.listenup.server.api.LibraryAdminServiceImpl].
 *
 * The base [SqlSyncableRepository] owns revision-bump / timestamp / created-vs-updated /
 * emit-after-commit orchestration; this class supplies only the folder-shaped pieces:
 *  - [substrate] — the [SyncableSubstrateQueries] adapter over `libraryFoldersQueries`
 *  - [readPayload] / [readPayloads] — root-row reads by id (tombstone-inclusive)
 *  - [writePayload] — insert-or-update inside the open transaction
 *  - `LibraryFolderSyncPayload.id`
 *
 * `idAsString(FolderId) = id.value` is load-bearing — Kotlin's default `toString()`
 * on a value class returns `"FolderId(value=foo)"`, which would corrupt every column
 * the id is written into.
 *
 * **Access-filtered sync.** `library_folders` rows carry absolute server filesystem paths,
 * so the firehose gates the whole domain admin-only: a non-admin catch-up/digest arrives
 * with a non-null [SqlFragment] `extraWhere` (the `WHERE 1 = 0` hidden subquery). The base
 * [SqlSyncableRepository] splices it engine-neutrally over the injected [SqlDriver]; this class
 * only wires that driver. The unfiltered (admin / null) path takes the base's substrate read
 * unchanged.
 *
 * Service-layer helpers beyond the base substrate (each runs in its own transaction):
 *  - [listByLibrary] — all live folders belonging to a library (the library→folder enumeration)
 *  - [findLiveByRootPath] — the live folder at a given path (the duplicate-path natural-key lookup)
 */
class LibraryFolderRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<LibraryFolderSyncPayload, FolderId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.LIBRARY_FOLDERS,
        clock = clock,
    ) {
    override fun idAsString(id: FolderId): String = id.value

    override val LibraryFolderSyncPayload.id: FolderId get() = FolderId(this.id)

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.libraryFoldersQueries].
     *
     * The canonical global adapter shape: the four substrate methods forward to the matching
     * generated query, mapping revision-cursor rows into the engine-neutral [IdRev]. The
     * `*ForUser` variants are intentionally left as the base's throwing defaults — folders are
     * global and never route through them.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.libraryFoldersQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.libraryFoldersQueries
                    .softDeleteById(
                        revision = revision,
                        updated_at = updatedAt,
                        deleted_at = deletedAt,
                        client_op_id = clientOpId,
                        id = id,
                    ).value

            override fun selectIdsAboveRevision(
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.libraryFoldersQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.libraryFoldersQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): LibraryFolderSyncPayload? =
        db.libraryFoldersQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<LibraryFolderSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.libraryFoldersQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    /** Tombstone projection — see [SqlSyncableRepository.minimizeTombstone]. */
    override fun minimizeTombstone(payload: LibraryFolderSyncPayload): LibraryFolderSyncPayload =
        payload.copy(libraryId = "", rootPath = "")

    override fun writePayload(
        value: LibraryFolderSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.libraryFoldersQueries.update(
                library_id = value.libraryId,
                root_path = value.rootPath,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.libraryFoldersQueries.insert(
                id = value.id,
                library_id = value.libraryId,
                root_path = value.rootPath,
                created_at = now,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Returns all live (non-tombstoned) folders belonging to [libraryId], in insertion
     * order. The library→folder enumeration the admin/onboarding paths use to resolve a
     * library's roots.
     */
    suspend fun listByLibrary(libraryId: String): List<LibraryFolderSyncPayload> =
        suspendTransaction(db) {
            db.libraryFoldersQueries
                .listByLibrary(libraryId)
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /**
     * Returns the live folder whose `root_path` equals [rootPath], or null when no live
     * row registers that path. Backs the duplicate-path guard — a path may live under at
     * most one non-deleted folder (enforced by the partial unique index).
     */
    suspend fun findLiveByRootPath(rootPath: String): LibraryFolderSyncPayload? =
        suspendTransaction(db) {
            db.libraryFoldersQueries
                .selectLiveByRootPath(rootPath)
                .executeAsOneOrNull()
                ?.toSyncPayload()
        }

    /**
     * Returns the most-recently-updated tombstoned folder whose `root_path` equals [rootPath] WITHIN
     * [libraryId], or null when no soft-deleted row registers that path under this library. Drives
     * folder-id REUSE on remove+re-add: re-adding a removed folder at the exact same path keeps its
     * stable id (and revives its books) instead of minting a fresh id that strands every client's saved
     * references. Scoped to [libraryId] so a tombstoned folder at the same path under a DIFFERENT
     * library is never reused. Exact-path match only — no prefix/fuzzy matching.
     */
    suspend fun findDeletedByRootPath(
        rootPath: String,
        libraryId: LibraryId,
    ): LibraryFolderSyncPayload? =
        suspendTransaction(db) {
            // The `deleted_at IS NOT NULL` predicate narrows the result's deleted_at to non-null, so
            // SQLDelight emits a bespoke row type here (not `Library_folders`); map with the column
            // mapper directly rather than through [toSyncPayload].
            db.libraryFoldersQueries
                .selectDeletedByRootPath(root_path = rootPath, library_id = libraryId.value) {
                    id,
                    libraryId0,
                    rootPath0,
                    createdAt,
                    revision,
                    updatedAt,
                    deletedAt,
                    _,
                    ->
                    LibraryFolderSyncPayload(
                        id = id,
                        libraryId = libraryId0,
                        rootPath = rootPath0,
                        revision = revision,
                        updatedAt = updatedAt,
                        createdAt = createdAt,
                        deletedAt = deletedAt,
                    )
                }.executeAsOneOrNull()
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: FolderId): String = idAsString(id)

    /** Maps a generated [Library_folders] row to the wire [LibraryFolderSyncPayload] DTO. */
    private fun Library_folders.toSyncPayload(): LibraryFolderSyncPayload =
        LibraryFolderSyncPayload(
            id = id,
            libraryId = library_id,
            rootPath = root_path,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}
