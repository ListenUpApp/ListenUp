package com.calypsan.listenup.server.services

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.sync.SittingStart
import com.calypsan.listenup.api.sync.SittingSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Sittings
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext

/**
 * What one sitting should say, before it has an identity: the reconciler's answer for one stretch of
 * someone's listening to one book. [SittingRepository.replaceFor] decides which stored row it is.
 */
internal data class SittingDraft(
    val startedAt: Long,
    val endedAt: Long,
    val listenedMs: Long,
    val beganRead: SittingStart,
    val finishedRead: Boolean,
)

/**
 * The `sittings` syncable repository: one row per person's unbroken time with one book. **Server-authored,
 * server-wins, book-gated but GLOBAL** (not per-user) — the `activities` shape: a row is visible only to
 * callers who can open its book, through the access-filtered pull ([driver]) and the firehose gate.
 *
 * Unlike `activities` it is not append-only: [replaceFor] rewrites a sitting in place as it grows and
 * tombstones one that late evidence merges into another, so the feed shows one row per sitting at every
 * moment, on every client, instead of a row per span that clients would have to merge.
 */
class SittingRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<SittingSyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.SITTINGS,
        clock = clock,
    ) {
    override val SittingSyncPayload.id: String
        get() = this.id

    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.sittingsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.sittingsQueries
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
                db.sittingsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.sittingsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): SittingSyncPayload? =
        db.sittingsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload()

    override fun readPayloads(idStrs: List<String>): List<SittingSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.sittingsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toPayload() }
    }

    /** A tombstone crosses the wire ungated, so it says nothing about who listened to what, or when. */
    override fun minimizeTombstone(payload: SittingSyncPayload): SittingSyncPayload =
        payload.copy(
            userId = "",
            bookId = "",
            startedAt = 0L,
            endedAt = 0L,
            listenedMs = 0L,
            beganRead = SittingStart.NONE,
            finishedRead = false,
        )

    override fun writePayload(
        value: SittingSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.sittingsQueries.update(
                started_at = value.startedAt,
                ended_at = value.endedAt,
                listened_ms = value.listenedMs,
                began_read = value.beganRead.storageName,
                finished_read = if (value.finishedRead) 1L else 0L,
                updated_at = now,
                revision = rev,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.sittingsQueries.insert(
                id = value.id,
                user_id = value.userId,
                book_id = value.bookId,
                started_at = value.startedAt,
                ended_at = value.endedAt,
                listened_ms = value.listenedMs,
                began_read = value.beganRead.storageName,
                finished_read = if (value.finishedRead) 1L else 0L,
                created_at = now,
                updated_at = now,
                revision = rev,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Makes [userId]'s live sittings of [bookId] exactly [desired], in one transaction, and returns how many
     * rows it wrote. Identity is kept: each desired sitting takes over the earliest-started live row it
     * overlaps, so a sitting that grows keeps its id, and a row two sittings were bridged into survives as
     * the earlier one. A desired sitting that overlaps no row is inserted; a live row no desired sitting
     * takes over is tombstoned. A row that already says what is desired is left alone — no revision, no
     * event — which is what makes rebuilding from unchanged evidence a no-op.
     */
    internal suspend fun replaceFor(
        userId: String,
        bookId: String,
        desired: List<SittingDraft>,
    ): Int {
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        return suspendTransaction(db) {
            val live =
                db.sittingsQueries
                    .selectLiveForUserBook(user_id = userId, book_id = bookId)
                    .executeAsList()
                    .map { it.toPayload() }
            val claimed = mutableSetOf<String>()
            var written = 0
            desired.sortedBy { it.startedAt }.forEach { draft ->
                val row =
                    live
                        .filter { it.id !in claimed && it.overlaps(draft) }
                        .minWithOrNull(compareBy({ it.startedAt }, { it.id }))
                row?.let { claimed += it.id }
                val wanted = draft.toPayload(id = row?.id ?: Uuid.random().toString(), userId = userId, bookId = bookId)
                if (row == null || row.copy(revision = wanted.revision) != wanted) {
                    upsertEventInOpenTransaction(value = wanted, suppressed = suppressed)
                    written++
                }
            }
            live
                .filterNot { it.id in claimed }
                .forEach { row ->
                    if (softDeleteInOpenTransaction(id = row.id, suppressed = suppressed) != null) written++
                }
            written
        }
    }

    /**
     * Every other user's live sittings that ended at or after [endedSince], newest first, on books they have
     * not finished — Discover's "recently listened" evidence. Viewer-agnostic: the caller filters by access.
     */
    suspend fun recentUnfinishedExcluding(
        excludeUserId: String,
        endedSince: Long,
    ): List<RecentSitting> =
        suspendTransaction(db) {
            db.sittingsQueries
                .selectRecentUnfinishedExcluding(exclude_user_id = excludeUserId, ended_since = endedSince) {
                    userId,
                    bookId,
                    endedAt,
                    ->
                    RecentSitting(userId = userId, bookId = bookId, endedAt = endedAt)
                }.executeAsList()
        }

    /** [userId]'s live sittings of [bookId], oldest first. */
    suspend fun liveFor(
        userId: String,
        bookId: String,
    ): List<SittingSyncPayload> =
        suspendTransaction(db) {
            db.sittingsQueries
                .selectLiveForUserBook(user_id = userId, book_id = bookId)
                .executeAsList()
                .map { it.toPayload() }
                .sortedBy { it.startedAt }
        }

    private fun Sittings.toPayload(): SittingSyncPayload =
        SittingSyncPayload(
            id = id,
            userId = user_id,
            bookId = book_id,
            startedAt = started_at,
            endedAt = ended_at,
            listenedMs = listened_ms,
            beganRead = SittingStart.entries.firstOrNull { it.storageName == began_read } ?: SittingStart.NONE,
            finishedRead = finished_read != 0L,
            revision = revision,
            deletedAt = deleted_at,
        )

    private companion object {
        const val SQLITE_IN_CHUNK = 900
    }
}

/** One sitting as Discover reads it: who, what, and when it ended. */
data class RecentSitting(
    val userId: String,
    val bookId: String,
    val endedAt: Long,
)

/** The stored spelling of a [SittingStart], the same word the wire uses. */
private val SittingStart.storageName: String
    get() =
        when (this) {
            SittingStart.NONE -> "none"
            SittingStart.FIRST -> "first"
            SittingStart.REREAD -> "reread"
        }

/** Whether this row and [draft] share any instant: the test for "the same sitting, grown or bridged". */
private fun SittingSyncPayload.overlaps(draft: SittingDraft): Boolean =
    startedAt <= draft.endedAt && endedAt >= draft.startedAt

private fun SittingDraft.toPayload(
    id: String,
    userId: String,
    bookId: String,
): SittingSyncPayload =
    SittingSyncPayload(
        id = id,
        userId = userId,
        bookId = bookId,
        startedAt = startedAt,
        endedAt = endedAt,
        listenedMs = listenedMs,
        beganRead = beganRead,
        finishedRead = finishedRead,
        revision = 0L,
        deletedAt = null,
    )
