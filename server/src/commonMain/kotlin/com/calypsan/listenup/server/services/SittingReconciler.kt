package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.activity.ListenSpan
import com.calypsan.listenup.api.dto.activity.RealListen
import com.calypsan.listenup.api.dto.activity.Sitting
import com.calypsan.listenup.api.dto.activity.groupIntoSittings
import com.calypsan.listenup.api.sync.SittingStart
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.SelectReadAnnouncements
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.util.KeyedMutex
import kotlin.time.Clock
import kotlinx.coroutines.withContext

/**
 * What [StatsRecorder] tells the sittings projection when the evidence behind it changes. An interface,
 * with a no-op [None], so the many recorder harnesses that never look at sittings need not build one;
 * production wires [SittingReconciler].
 */
interface SittingsHook {
    /** The evidence for [userId]'s time with [bookId] changed: rebuild those sittings. */
    suspend fun reconcile(
        userId: String,
        bookId: String,
    )

    /** Much of [userId]'s history changed at once (an import): rebuild every one of their sittings. */
    suspend fun reconcileUser(userId: String)

    /** Keeps no sittings. */
    object None : SittingsHook {
        override suspend fun reconcile(
            userId: String,
            bookId: String,
        ) = Unit

        override suspend fun reconcileUser(userId: String) = Unit
    }
}

/**
 * Builds the `sittings` rows from the evidence, the one place that does. For one (user, book) it reads
 * every live closed listening span and the book's started/finished announcements, groups the spans by the
 * shared rule ([groupIntoSittings]: an idle gap over [RealListen.SITTING_GAP_MS] splits, another book in
 * between does not, time two devices played at once counts once), keeps the real listens
 * ([Sitting.isRealListen]) and hands them to [SittingRepository.replaceFor], which keeps each sitting's id.
 *
 * A pure function of the evidence, so it is order-independent and crash-healing — the same posture as
 * `deriveUserStats` — and rebuilding from unchanged evidence writes nothing. Calls for the same user are
 * serialized, so two evidence changes can never race to insert the same sitting twice.
 *
 * Evidence today is closed spans only. What the server hears of a span still open is meant to join it here
 * as more [ListenSpan]s, which is why the row stores the sitting and not the spans it came from.
 */
class SittingReconciler(
    private val db: ListenUpDatabase,
    private val sittings: SittingRepository,
    private val bus: ChangeBus,
    private val clock: Clock = Clock.System,
) : SittingsHook {
    private val userLock = KeyedMutex()

    override suspend fun reconcile(
        userId: String,
        bookId: String,
    ) {
        rebuildBook(userId, bookId)
    }

    /** [reconcile], returning how many rows it wrote. */
    internal suspend fun rebuildBook(
        userId: String,
        bookId: String,
    ): Int = userLock.withLock(userId) { rebuild(userId, bookId) }

    /**
     * Rebuilds every sitting of [userId]'s, firehose-suppressed: a whole history is a burst the lossy live
     * tail must not carry. When it wrote anything it broadcasts one `LibraryDataChanged`, as every suppressed
     * bulk write must, so connected clients reconcile the rows now rather than on their next reconnect.
     */
    override suspend fun reconcileUser(userId: String) {
        rebuildUser(userId)
    }

    /** [reconcileUser], returning how many rows it wrote. */
    internal suspend fun rebuildUser(userId: String): Int {
        val written =
            withContext(FirehoseSuppressed) {
                userLock.withLock(userId) {
                    val books =
                        suspendTransaction(db) { db.sittingsQueries.selectBooksForUser(userId).executeAsList() }
                    books.sumOf { bookId -> rebuild(userId, bookId) }
                }
            }
        if (written > 0) bus.broadcastControl(SyncControl.LibraryDataChanged)
        return written
    }

    private suspend fun rebuild(
        userId: String,
        bookId: String,
    ): Int {
        val (spans, announcements) =
            suspendTransaction(db) {
                val spans =
                    db.sittingsQueries
                        .selectSpanEvidence(user_id = userId, book_id = bookId) { startedAt, endedAt ->
                            ListenSpan(userId = userId, bookId = bookId, startedAtMs = startedAt, endedAtMs = endedAt)
                        }.executeAsList()
                spans to db.sittingsQueries.selectReadAnnouncements(user_id = userId, book_id = bookId).executeAsList()
            }
        val nowMs = clock.now().toEpochMilliseconds()
        val desired =
            spans
                .groupIntoSittings { it }
                .filter { it.isRealListen }
                .map { it.toDraft(announcements, nowMs) }
        return sittings.replaceFor(userId = userId, bookId = bookId, desired = desired)
    }
}

/**
 * The row for this sitting. Its end is never later than [nowMs]: a device clock running ahead must not
 * float a sitting above everything that really happened since.
 *
 * A start announcement belongs to the sitting it falls in or that began within [RealListen.SITTING_GAP_MS]
 * after it (a book opened, then played a little later); a finish to the sitting it falls in or that ended
 * within the gap before it. Sittings of one book are more than the gap apart, so each announcement belongs
 * to at most one.
 */
private fun Sitting<ListenSpan>.toDraft(
    announcements: List<SelectReadAnnouncements>,
    nowMs: Long,
): SittingDraft {
    val endedAt = minOf(endedAtMs, nowMs)
    val started =
        announcements
            .filter { it.type == ActivityType.STARTED_BOOK }
            .filter { it.occurred_at in (startedAtMs - RealListen.SITTING_GAP_MS)..endedAtMs }
            .maxByOrNull { it.occurred_at }
    val finished =
        announcements.any { announcement ->
            announcement.type == ActivityType.FINISHED_BOOK &&
                announcement.occurred_at in startedAtMs..(endedAtMs + RealListen.SITTING_GAP_MS)
        }
    return SittingDraft(
        startedAt = minOf(startedAtMs, endedAt),
        endedAt = endedAt,
        listenedMs = listenedMs,
        beganRead =
            when {
                started == null -> SittingStart.NONE
                started.is_reread == 1L -> SittingStart.REREAD
                else -> SittingStart.FIRST
            },
        finishedRead = finished,
    )
}
