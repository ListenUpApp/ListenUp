package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import kotlin.time.Clock

/**
 * Keeping one book off Hardcover, and syncing it again (#1541) — [setSynced], behind
 * [com.calypsan.listenup.api.HardcoverService.setBookSynced]. Nothing here ever calls Hardcover: as with a
 * disconnect, what Hardcover holds stays as it is. Each change runs as the listener's only Hardcover
 * conversation ([HardcoverUserGate]), so no push step or pull page lands half-way through it.
 *
 * Keeping it off:
 * - a To Read entry Hardcover's Want to Read added comes off ([HardcoverWantToRead.release]);
 * - then, in one transaction, the exclusion is recorded, the book's queued pushes go, and its pulled reads
 *   go by the pull's own deletion ([forgetPulledReadsOf]);
 * - when any pulled read went, every client re-reads Readers ([SyncControl.ActiveSessionsChanged], a
 *   content-free broadcast — other listeners see this one's Hardcover reads too);
 * - a send of earlier books this emptied settles, and the listener's Connected is republished.
 * The link stays, so syncing again never asks for a match again.
 */
class HardcoverKeepOff(
    private val sql: ListenUpDatabase,
    private val access: BookAccessPolicy,
    private val wantToRead: HardcoverWantToRead,
    private val gate: HardcoverUserGate,
    private val bus: ChangeBus,
    private val history: HardcoverHistoryProgress,
    private val activity: HardcoverSyncActivity,
    private val clock: Clock = Clock.System,
) {
    /**
     * Keeps [bookId] off Hardcover for [userId] ([synced] false), or syncs it again (true). Idempotent: keeping
     * it off twice keeps the first time. [BookError.NotFound] for a book they can't see.
     */
    suspend fun setSynced(
        userId: String,
        role: UserRole,
        bookId: String,
        synced: Boolean,
    ): AppResult<Unit> {
        if (!access.canAccess(userId, role, bookId)) {
            return AppResult.Failure(BookError.NotFound(debugInfo = "bookId=$bookId"))
        }
        return if (synced) syncAgain(userId, bookId) else keepOff(userId, bookId)
    }

    /** The books [userId] keeps off Hardcover that they can still see, by title. */
    suspend fun keptOffBooks(
        userId: String,
        role: UserRole,
    ): List<String> =
        suspendTransaction(sql) { sql.hardcoverBookExclusionsQueries.keptOffBooks(userId).executeAsList() }
            .filter { access.canAccess(userId, role, it) }

    private suspend fun keepOff(
        userId: String,
        bookId: String,
    ): AppResult<Unit> {
        val outcome =
            gate.withUser(userId) {
                when (val released = wantToRead.release(userId, bookId)) {
                    is AppResult.Failure -> released
                    is AppResult.Success -> AppResult.Success(excludeAndForget(userId, bookId))
                }
            }
        if (outcome is AppResult.Success) {
            if (outcome.data) bus.broadcastControl(SyncControl.ActiveSessionsChanged)
            history.settle(userId)
            activity.keptOffChanged(userId)
        }
        return outcome.map { }
    }

    /** One transaction: the exclusion, the book's queued pushes gone, its pulled reads gone. True when any read went. */
    private suspend fun excludeAndForget(
        userId: String,
        bookId: String,
    ): Boolean {
        val at = clock.now().toEpochMilliseconds()
        return suspendTransaction(sql) {
            sql.hardcoverBookExclusionsQueries.insertExclusion(user_id = userId, book_id = bookId, excluded_at = at)
            sql.hardcoverOutboxQueries.deleteForBook(user_id = userId, book_id = bookId)
            sql.hasPulledReadsOf(userId, bookId).also { sql.forgetPulledReadsOf(userId, bookId) }
        }
    }

    private suspend fun syncAgain(
        userId: String,
        bookId: String,
    ): AppResult<Unit> {
        val wasKeptOff =
            gate.withUser(userId) {
                suspendTransaction(sql) {
                    val excludedAt = sql.hardcoverBookExclusionsQueries.selectExclusion(userId, bookId).executeAsOneOrNull()
                    if (excludedAt != null) sql.hardcoverBookExclusionsQueries.deleteExclusion(userId, bookId)
                    excludedAt != null
                }
            }
        if (wasKeptOff) activity.keptOffChanged(userId)
        return AppResult.Success(Unit)
    }
}
