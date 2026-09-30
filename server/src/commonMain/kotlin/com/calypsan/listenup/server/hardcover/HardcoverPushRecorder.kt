package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.activity.RealListen
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * The listen-through id of a book already in progress before listen-throughs existed: it has no
 * `listen_throughs` row, so no `started_at` to be known by.
 */
const val LEGACY_LISTEN_THROUGH: Long = 0L

private const val MILLIS_PER_SECOND = 1_000L

/**
 * What [com.calypsan.listenup.server.services.StatsRecorder] tells Hardcover sync, at the three moments
 * spec B2 pushes from. [None] does nothing: tests and wiring without Hardcover use it.
 */
interface HardcoverPushHook {
    /** The listen-through that began at [startedAt] just crossed the real-listen line. */
    suspend fun onRealStart(
        userId: String,
        bookId: String,
        startedAt: Long,
        isReread: Boolean,
    )

    /** A listening session on [bookId] closed at [positionMs] into the book. */
    suspend fun onSessionClosed(
        userId: String,
        bookId: String,
        positionMs: Long,
    )

    /** The coverage rule appended a new read of [bookId], finished at [finishedAt]. */
    suspend fun onReadAppended(
        userId: String,
        bookId: String,
        finishedAt: Long,
    )

    /** The hook that pushes nothing. */
    companion object {
        /** Pushes nothing. */
        val None: HardcoverPushHook =
            object : HardcoverPushHook {
                override suspend fun onRealStart(
                    userId: String,
                    bookId: String,
                    startedAt: Long,
                    isReread: Boolean,
                ) = Unit

                override suspend fun onSessionClosed(
                    userId: String,
                    bookId: String,
                    positionMs: Long,
                ) = Unit

                override suspend fun onReadAppended(
                    userId: String,
                    bookId: String,
                    finishedAt: Long,
                ) = Unit
            }
    }
}

/** Wakes a user's push lane. [HardcoverPushWorker] is the real one. */
fun interface HardcoverPushNudge {
    /** [userId] has something new in the outbox. */
    fun nudge(userId: String)
}

/**
 * Turns [HardcoverPushHook] events into outbox rows (spec B2), then nudges the user's lane:
 * - START when a listen-through really starts; it also lifts a suppression of any earlier one.
 * - PROGRESS when a session closes on a listen-through that has really started (or predates
 *   listen-throughs) and hasn't finished; due no sooner than a sitting gap after the last progress
 *   push, and coalesced with any PROGRESS already queued.
 * - FINISH when a completion appended a read.
 * Nothing is queued for a user with no Hardcover connection row, or for a suppressed listen-through.
 */
class HardcoverPushRecorder(
    private val sql: ListenUpDatabase,
    private val connections: HardcoverConnectionStore,
    private val outbox: HardcoverOutbox,
    private val links: HardcoverBookLinkStore,
    private val nudge: HardcoverPushNudge,
    private val clock: Clock = Clock.System,
) : HardcoverPushHook {
    override suspend fun onRealStart(
        userId: String,
        bookId: String,
        startedAt: Long,
        isReread: Boolean,
    ) {
        if (!connections.hasConnection(userId)) return
        links.clearSuppressionUnlessFor(userId, bookId, startedAt)
        if (links.linkFor(userId, bookId)?.suppressedListenThrough == startedAt) return
        outbox.enqueueStart(userId, bookId, listenThrough = startedAt, startedAt = startedAt, isReread = isReread)
        nudge.nudge(userId)
    }

    override suspend fun onSessionClosed(
        userId: String,
        bookId: String,
        positionMs: Long,
    ) {
        if (!connections.hasConnection(userId)) return
        val listenThrough = listenThroughTakingProgress(userId, bookId) ?: return
        val link = links.linkFor(userId, bookId)
        if (link?.suppressedListenThrough == listenThrough) return
        val now = clock.now().toEpochMilliseconds()
        val notBefore = link?.lastProgressPushedAt?.let { it + RealListen.SITTING_GAP_MS } ?: now
        outbox.enqueueProgress(userId, bookId, listenThrough, positionMs / MILLIS_PER_SECOND, notBefore)
        nudge.nudge(userId)
    }

    override suspend fun onReadAppended(
        userId: String,
        bookId: String,
        finishedAt: Long,
    ) {
        if (!connections.hasConnection(userId)) return
        val listenThrough =
            suspendTransaction(sql) { sql.listenThroughsQueries.selectCurrent(userId, bookId).executeAsOneOrNull() }?.started_at
                ?: LEGACY_LISTEN_THROUGH
        if (links.linkFor(userId, bookId)?.suppressedListenThrough == listenThrough) return
        outbox.enqueueFinish(userId, bookId, listenThrough, finishedAt)
        nudge.nudge(userId)
    }

    /**
     * The listen-through a closed session belongs to, when it may push progress: it has really started
     * (or predates listen-throughs) and no finish has closed it. Null otherwise.
     */
    private suspend fun listenThroughTakingProgress(
        userId: String,
        bookId: String,
    ): Long? =
        suspendTransaction(sql) {
            val current = sql.listenThroughsQueries.selectCurrent(userId, bookId).executeAsOneOrNull()
            val lastFinish = sql.bookReadsQueries.latestFinishForUserBook(userId, bookId).executeAsOneOrNull()?.finished_at
            val listenThrough = current?.started_at ?: LEGACY_LISTEN_THROUGH
            when {
                current != null && current.real_started_at == null -> null
                lastFinish != null && lastFinish >= listenThrough -> null
                else -> listenThrough
            }
        }
}
