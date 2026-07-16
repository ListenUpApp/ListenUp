package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.LibrarySyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.Libraries
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for libraries — a **global (cross-user)** aggregate,
 * the same global template as [com.calypsan.listenup.server.sync.TagRepository] (not
 * user-scoped: it never touches the `*ForUser` substrate variants).
 *
 * The base [SqlSyncableRepository] owns revision-bump / timestamp / created-vs-updated /
 * emit-after-commit orchestration; this class supplies only the library-shaped pieces:
 *  - [substrate] — the [SyncableSubstrateQueries] adapter over `librariesQueries`
 *  - [readPayload] / [readPayloads] — root-row reads by id (tombstone-inclusive)
 *  - [writePayload] — insert-or-update inside the open transaction
 *  - `LibrarySyncPayload.id`
 *
 * `idAsString(LibraryId) = id.value` is load-bearing — Kotlin's default `toString()`
 * on a value class returns `"LibraryId(value=foo)"`, which would corrupt every column
 * the id is written into.
 *
 * **The `inbox_enabled` gate is OFF-payload.** It is a server-side scanner gate read by
 * the ingest path, deliberately absent from [LibrarySyncPayload] (not member-synced). It
 * is written/read by [setInboxEnabled] / [readInboxEnabled] through dedicated queries
 * (`setInboxEnabled` / `selectInboxEnabled`), never through the syncable `update` — so a
 * normal library upsert never clobbers it, and a gate toggle never rewrites the synced
 * library fields.
 */
class LibraryRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<LibrarySyncPayload, LibraryId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.LIBRARIES,
        clock = clock,
    ) {
    override fun idAsString(id: LibraryId): String = id.value

    override val LibrarySyncPayload.id: LibraryId get() = LibraryId(this.id)

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.librariesQueries].
     *
     * The canonical global adapter shape: the four substrate methods forward to the matching
     * generated query, mapping revision-cursor rows into the engine-neutral [IdRev]. The
     * `*ForUser` variants are intentionally left as the base's throwing defaults — libraries
     * are global and never route through them.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.librariesQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.librariesQueries
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
                db.librariesQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.librariesQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): LibrarySyncPayload? =
        db.librariesQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<LibrarySyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.librariesQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: LibrarySyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            // `inbox_enabled` is intentionally omitted — the syncable update must never clobber
            // the off-payload server-side gate (its own setInboxEnabled query owns that column).
            db.librariesQueries.update(
                name = value.name,
                metadata_precedence = value.metadataPrecedence,
                access_mode = value.accessMode,
                created_by_user_id = value.createdByUserId,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.librariesQueries.insert(
                id = value.id,
                name = value.name,
                metadata_precedence = value.metadataPrecedence,
                access_mode = value.accessMode,
                created_by_user_id = value.createdByUserId,
                created_at = now,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Reads the `inbox_enabled` gate for [libraryId], or `false` when no live row
     * exists. The flag lives only on the `libraries` table — it is deliberately absent
     * from [LibrarySyncPayload] (a server-side scanner gate, not member-synced) — so the
     * admin-facing read path resolves it directly through `selectInboxEnabled`, which
     * already filters to non-tombstoned rows.
     */
    suspend fun readInboxEnabled(libraryId: LibraryId): Boolean =
        suspendTransaction(db) {
            db.librariesQueries
                .selectInboxEnabled(libraryId.value)
                .executeAsOneOrNull()
                ?.let { it == 1L }
                ?: false
        }

    /**
     * Sets the `inbox_enabled` gate for [libraryId] to [enabled], bumping the
     * library's revision and publishing a [SyncEvent.Updated] so connected clients
     * reconcile reactively. The flag itself is not carried on [LibrarySyncPayload]
     * (server-side scanner gate, not member-synced), so this writes the column
     * directly through `setInboxEnabled` rather than routing through [upsert] — a
     * gate toggle must not rewrite the synced library fields.
     *
     * Returns [LibraryError.NotFound] when no live row exists for [libraryId]. The
     * `SyncEvent.Updated` emit is deferred to after-commit via [emitAfterCommit],
     * the same publish-order-preserving path the base's own writes use.
     */
    suspend fun setInboxEnabled(
        libraryId: LibraryId,
        enabled: Boolean,
    ): AppResult<Unit> =
        suspendTransaction(db) {
            val idStr = libraryId.value
            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            val rowsAffected =
                db.librariesQueries
                    .setInboxEnabled(
                        inbox_enabled = if (enabled) 1L else 0L,
                        revision = rev,
                        updated_at = now,
                        client_op_id = null,
                        id = idStr,
                    ).value
            if (rowsAffected == 0L) {
                AppResult.Failure(LibraryError.NotFound())
            } else {
                val saved = readPayload(idStr) ?: error("library $idStr vanished mid-transaction")
                emitAfterCommit(
                    event =
                        SyncEvent.Updated(
                            id = idStr,
                            revision = rev,
                            occurredAt = now,
                            clientOpId = null,
                            payload = saved,
                        ),
                )
                AppResult.Success(Unit)
            }
        }

    /**
     * Stamps the first-ever scan-completion time for [libraryId], bumping the library's revision and
     * updated_at, and publishing a [SyncEvent.Updated] so connected clients reconcile the flag
     * reactively (it drives their initial-population gate). The `initial_scan_completed_at IS NULL`
     * guard makes this **first-only** — a rescan of an already-populated library writes zero rows and
     * emits nothing, so the "Building your library" screen never re-shows.
     *
     * Like the `inbox_enabled` gate this is an OFF-payload column write (never through the syncable
     * `update`), so it never rewrites the synced library fields. Returns `true` when a row was actually
     * stamped (first completion), `false` when it was already stamped or no live row exists — the emit
     * is deferred to after-commit via [emitAfterCommit] only on the `true` path.
     */
    suspend fun markInitialScanCompleted(
        libraryId: LibraryId,
        completedAt: Long,
    ): Boolean =
        suspendTransaction(db) {
            val idStr = libraryId.value
            val rev = nextRevision()
            val rowsAffected =
                db.librariesQueries
                    .markInitialScanCompleted(
                        completed_at = completedAt,
                        revision = rev,
                        updated_at = completedAt,
                        id = idStr,
                    ).value
            if (rowsAffected == 0L) {
                false
            } else {
                val saved = readPayload(idStr) ?: error("library $idStr vanished mid-transaction")
                emitAfterCommit(
                    event =
                        SyncEvent.Updated(
                            id = idStr,
                            revision = rev,
                            occurredAt = completedAt,
                            clientOpId = null,
                            payload = saved,
                        ),
                )
                true
            }
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: LibraryId): String = idAsString(id)

    /** Maps a generated [Libraries] row to the wire [LibrarySyncPayload] DTO (drops `inbox_enabled`). */
    private fun Libraries.toSyncPayload(): LibrarySyncPayload =
        LibrarySyncPayload(
            id = id,
            name = name,
            metadataPrecedence = metadata_precedence,
            accessMode = access_mode,
            createdByUserId = created_by_user_id,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
            initialScanCompletedAt = initial_scan_completed_at,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}
