@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.db.ActiveSessionTable
import com.calypsan.listenup.server.sync.ChangeBus
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Server-derived presence store for per-user active listening sessions.
 *
 * Presence (who is currently listening, and who is reading a given book) is owned by
 * the server: clients do not sync this domain, they query it through [SocialService].
 * One live row exists per `(userId, bookId)` pair while that user is actively
 * listening; the row is created/refreshed by [startOrRefresh], removed by
 * [deleteForUserBook] when the book is finished, and swept after a staleness threshold
 * by [com.calypsan.listenup.server.scheduler.ActiveSessionCleanupTask].
 *
 * Mutations publish a content-free [SyncControl.ActiveSessionsChanged] broadcast nudge
 * — a re-derive hint that carries no per-user or per-resource data, so it cannot leak.
 * Connected clients re-query presence on receipt. The nudge is published after the
 * transaction commits, so subscribers never observe a pre-commit state.
 */
class ActiveSessionRepository(
    private val db: Database,
    private val bus: ChangeBus,
    private val clock: Clock = Clock.System,
) {
    /**
     * Create a live session row for `(userId, bookId)`, or refresh the existing one's
     * `updatedAt` if it is already live. Returns `true` when a new row was inserted,
     * `false` when an existing row was refreshed.
     *
     * Publishes [SyncControl.ActiveSessionsChanged] only on insert: a refresh does not
     * change who is present, so it needs no nudge.
     */
    suspend fun startOrRefresh(
        userId: String,
        bookId: String,
    ): Boolean {
        val created =
            suspendTransaction(db) {
                val now = clock.now().toEpochMilliseconds()
                val existing =
                    ActiveSessionTable
                        .selectAll()
                        .where {
                            (ActiveSessionTable.userId eq userId) and
                                (ActiveSessionTable.bookId eq bookId) and
                                ActiveSessionTable.deletedAt.isNull()
                        }.firstOrNull()
                if (existing != null) {
                    ActiveSessionTable.update({
                        ActiveSessionTable.sessionId eq existing[ActiveSessionTable.sessionId]
                    }) { it[updatedAt] = now }
                    false
                } else {
                    ActiveSessionTable.insert {
                        it[sessionId] = Uuid.random().toString()
                        it[ActiveSessionTable.userId] = userId
                        it[ActiveSessionTable.bookId] = bookId
                        it[startedAt] = now
                        it[updatedAt] = now
                        it[createdAt] = now
                        it[revision] = 0L
                        it[deletedAt] = null
                    }
                    true
                }
            }
        if (created) bus.broadcastControl(SyncControl.ActiveSessionsChanged)
        return created
    }

    /**
     * Live presence across all users except [excludeUserId] — the "currently listening"
     * feed, with the requesting user filtered out so they don't see themselves.
     */
    suspend fun listCurrentlyListening(excludeUserId: String): List<ActiveSessionRow> =
        suspendTransaction(db) {
            ActiveSessionTable
                .selectAll()
                .where { (ActiveSessionTable.userId neq excludeUserId) and ActiveSessionTable.deletedAt.isNull() }
                .map { it.toRow() }
        }

    /**
     * Live presence on [bookId] except [excludeUserId] — the "readers of this book"
     * surface on BookDetail, with the requesting user filtered out.
     */
    suspend fun listReadersForBook(
        bookId: String,
        excludeUserId: String,
    ): List<ActiveSessionRow> =
        suspendTransaction(db) {
            ActiveSessionTable
                .selectAll()
                .where {
                    (ActiveSessionTable.bookId eq bookId) and
                        (ActiveSessionTable.userId neq excludeUserId) and
                        ActiveSessionTable.deletedAt.isNull()
                }.map { it.toRow() }
        }

    /**
     * Remove the session row(s) for `(userId, bookId)`. Called by the completion
     * cascade in [PlaybackPositionRepository.recordPosition] when a book's `finished`
     * flag flips `false→true`. Publishes [SyncControl.ActiveSessionsChanged] when a row
     * was actually removed; a no-op delete publishes nothing.
     */
    suspend fun deleteForUserBook(
        userId: String,
        bookId: String,
    ) {
        val deleted =
            suspendTransaction(db) {
                ActiveSessionTable.deleteWhere {
                    (ActiveSessionTable.userId eq userId) and (ActiveSessionTable.bookId eq bookId)
                } > 0
            }
        if (deleted) bus.broadcastControl(SyncControl.ActiveSessionsChanged)
    }
}

/** One live presence row: who is listening to which book, and when they started. */
data class ActiveSessionRow(
    val userId: String,
    val bookId: String,
    val startedAt: Long,
)

private fun ResultRow.toRow() =
    ActiveSessionRow(
        userId = this[ActiveSessionTable.userId],
        bookId = this[ActiveSessionTable.bookId],
        startedAt = this[ActiveSessionTable.startedAt],
    )
