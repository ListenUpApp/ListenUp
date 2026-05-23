package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ActiveSessionSyncPayload
import com.calypsan.listenup.server.db.ActiveSessionTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable-domain repository for per-user active listening sessions (Playback P3).
 *
 * One row exists per active listener — written by the client when playback starts
 * and deleted when playback stops, the book is finished, or the cleanup sweep
 * evicts it after the staleness threshold.
 *
 * The primary key is `session_id` (not the substrate's conventional `id`), so
 * [idColumn] is overridden to point at [ActiveSessionTable.sessionId].
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest`
 * call routes through the per-user dimension of the substrate.
 *
 * [deleteForUserBook] is the completion cascade entry point: when
 * [PlaybackPositionRepository.recordPosition] detects a `finished` flip it calls
 * this method inside the same outer `suspendTransaction` to delete the active
 * session row atomically with the position write.
 */
class ActiveSessionRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<ActiveSessionSyncPayload, String>(
        db = db,
        table = ActiveSessionTable,
        bus = bus,
        registry = registry,
        domainName = "active_sessions",
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val elementSerializer: KSerializer<ActiveSessionSyncPayload> =
        ActiveSessionSyncPayload.serializer()

    // session_id is the PK, not the substrate's conventional `id` column.
    override fun idColumn(): Column<String> = ActiveSessionTable.sessionId

    override val ActiveSessionSyncPayload.id: String get() = this.sessionId

    override fun ActiveSessionSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): ActiveSessionSyncPayload? =
        ActiveSessionTable
            .selectAll()
            .where { ActiveSessionTable.sessionId eq idStr }
            .firstOrNull()
            ?.let { row ->
                ActiveSessionSyncPayload(
                    sessionId = row[ActiveSessionTable.sessionId],
                    bookId = row[ActiveSessionTable.bookId],
                    startedAt = row[ActiveSessionTable.startedAt],
                    revision = row[ActiveSessionTable.revision],
                    updatedAt = row[ActiveSessionTable.updatedAt],
                    createdAt = row[ActiveSessionTable.createdAt],
                    deletedAt = row[ActiveSessionTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: ActiveSessionSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        requireNotNull(userId) { "ActiveSessionRepository.writePayload requires a userId" }
        if (existed) {
            ActiveSessionTable.update({ ActiveSessionTable.sessionId eq value.sessionId }) { stmt ->
                stmt[ActiveSessionTable.bookId] = value.bookId
                stmt[ActiveSessionTable.startedAt] = value.startedAt
                stmt[ActiveSessionTable.revision] = rev
                stmt[ActiveSessionTable.updatedAt] = now
                stmt[ActiveSessionTable.deletedAt] = null
                stmt[ActiveSessionTable.clientOpId] = clientOpId
            }
        } else {
            ActiveSessionTable.insert { stmt ->
                stmt[ActiveSessionTable.sessionId] = value.sessionId
                stmt[ActiveSessionTable.userId] = userId
                stmt[ActiveSessionTable.bookId] = value.bookId
                stmt[ActiveSessionTable.startedAt] = value.startedAt
                stmt[ActiveSessionTable.revision] = rev
                stmt[ActiveSessionTable.createdAt] = now
                stmt[ActiveSessionTable.updatedAt] = now
                stmt[ActiveSessionTable.deletedAt] = null
                stmt[ActiveSessionTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Delete the active-session row(s) for the `(userId, bookId)` pair. Idempotent:
     * a delete when no matching row exists is a no-op. Does NOT publish a substrate
     * event — the caller (`PlaybackPositionRepository.recordPosition`) is already
     * inside an outer `suspendTransaction` and the row is hard-deleted (not
     * soft-deleted) so there is no revision bump to announce. Clients observe the
     * absence via the next digest / pull cycle.
     *
     * Called from [PlaybackPositionRepository.recordPosition]'s atomic side-effect
     * branch when a book's `finished` flag flips `false→true`. The outer
     * `suspendTransaction(db)` makes this hard-delete atomic with the position write.
     */
    suspend fun deleteForUserBook(
        userId: String,
        bookId: String,
    ): AppResult<Unit> =
        suspendTransaction(db) {
            ActiveSessionTable.deleteWhere {
                (ActiveSessionTable.userId eq userId) and
                    (ActiveSessionTable.bookId eq bookId)
            }
            AppResult.Success(Unit)
        }

    /**
     * Return all active sessions for the given user, excluding soft-deleted rows.
     * Used in tests to assert repository state.
     */
    internal suspend fun getForUser(userId: String): List<ActiveSessionSyncPayload> =
        suspendTransaction(db) {
            ActiveSessionTable
                .selectAll()
                .where {
                    (ActiveSessionTable.userId eq userId) and
                        (ActiveSessionTable.deletedAt eq null)
                }.map { row ->
                    ActiveSessionSyncPayload(
                        sessionId = row[ActiveSessionTable.sessionId],
                        bookId = row[ActiveSessionTable.bookId],
                        startedAt = row[ActiveSessionTable.startedAt],
                        revision = row[ActiveSessionTable.revision],
                        updatedAt = row[ActiveSessionTable.updatedAt],
                        createdAt = row[ActiveSessionTable.createdAt],
                        deletedAt = row[ActiveSessionTable.deletedAt],
                    )
                }
        }
}
