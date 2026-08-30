package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import kotlin.time.Clock
import kotlin.uuid.Uuid

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
 * Reimplemented as a PLAIN SQLDelight repository over [ListenUpDatabase]: although the
 * `active_sessions` row carries the sync-discipline columns (it shares the table with the
 * syncable substrate's column shape), presence is never actually synced, so there is no
 * `SqlSyncableRepository` machinery here — the inserts still stamp `revision = 0` and the
 * substrate timestamps to keep the row identical to the prior Exposed insert.
 *
 * Mutations publish a content-free [SyncControl.ActiveSessionsChanged] broadcast nudge
 * — a re-derive hint that carries no per-user or per-resource data, so it cannot leak.
 * Connected clients re-query presence on receipt. Standalone calls (cleanup, finish-flip)
 * publish after their own transaction commits; when [startOrRefresh] runs inside a caller's
 * outer transaction (e.g. `recordPosition`), the nudge nests within that transaction — matching
 * the [com.calypsan.listenup.server.sync.SyncableRepository] convention. This is harmless: the
 * nudge is only a re-derive hint, and a client that re-queries before the outer commit simply
 * sees the prior state and re-derives again on the next nudge.
 */
class ActiveSessionRepository(
    private val db: ListenUpDatabase,
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
                    db.activeSessionsQueries
                        .selectLiveForUserBook(user_id = userId, book_id = bookId)
                        .executeAsOneOrNull()
                if (existing != null) {
                    db.activeSessionsQueries.refreshUpdatedAt(updated_at = now, session_id = existing.session_id)
                    false
                } else {
                    db.activeSessionsQueries.insert(
                        session_id = Uuid.random().toString(),
                        user_id = userId,
                        book_id = bookId,
                        started_at = now,
                        created_at = now,
                        updated_at = now,
                    )
                    true
                }
            }
        if (created) bus.broadcastControl(SyncControl.ActiveSessionsChanged)
        return created
    }

    /**
     * Live presence across all users except [excludeUserId] — the "currently listening"
     * feed, with the requesting user filtered out so they don't see themselves — limited to
     * rows refreshed at or after [liveSince].
     *
     * The caller owns the window: this repository stores presence, but what counts as "now" is a
     * presentation decision, and [com.calypsan.listenup.server.api.SocialServiceImpl] is where it is
     * stated.
     */
    suspend fun listCurrentlyListening(
        excludeUserId: String,
        liveSince: Long,
    ): List<ActiveSessionRow> =
        suspendTransaction(db) {
            db.activeSessionsQueries
                .selectCurrentlyListeningExcluding(exclude_user_id = excludeUserId, live_since = liveSince)
                .executeAsList()
                .map { ActiveSessionRow(userId = it.user_id, bookId = it.book_id, startedAt = it.started_at) }
        }

    /**
     * Every non-deleted presence row on [bookId] except [excludeUserId]'s.
     *
     * **Not a user-facing surface, despite the name.** BookDetail's readership comes from
     * `SocialService.bookReadership`, which reads `PlaybackPositionRepository.listInProgressForBook`;
     * this method has no production caller. It survives as the observation point roughly eighteen
     * assertions use to ask "is there a presence row for this book" — `ActiveSessionCleanupTaskTest`,
     * `RecordPositionPresenceTest` and `PlaybackPositionRepositoryTest` among them.
     *
     * That is why it takes **no `liveSince` window**, unlike [listCurrentlyListening]. Those specs
     * deliberately seed stale rows and assert they are still present *before* the sweep removes them;
     * a staleness filter here would hide exactly what they exist to observe. Raw row presence is the
     * contract. If this ever gains a production caller, it needs a window — and then it needs its own
     * name, because it would no longer be the same question.
     */
    suspend fun listReadersForBook(
        bookId: String,
        excludeUserId: String,
    ): List<ActiveSessionRow> =
        suspendTransaction(db) {
            db.activeSessionsQueries
                .selectReadersForBookExcluding(book_id = bookId, exclude_user_id = excludeUserId)
                .executeAsList()
                .map { ActiveSessionRow(userId = it.user_id, bookId = it.book_id, startedAt = it.started_at) }
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
                db.activeSessionsQueries.deleteForUserBook(user_id = userId, book_id = bookId)
                db.activeSessionsQueries.changes().executeAsOne() > 0
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
