package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** What one outbox row asks Hardcover to do (the `payload` JSON; `op` names the subtype). */
@Serializable
sealed interface HardcoverPushPayload {
    /** The listen-through that began at [startedAt] crossed the real-listen line: "Currently Reading". */
    @Serializable
    @SerialName("START")
    data class Start(
        @SerialName("startedAt") val startedAt: Long,
        @SerialName("isReread") val isReread: Boolean,
    ) : HardcoverPushPayload

    /** The listener is [positionSeconds] into the book. */
    @Serializable
    @SerialName("PROGRESS")
    data class Progress(
        @SerialName("positionSeconds") val positionSeconds: Long,
    ) : HardcoverPushPayload

    /**
     * The listener finished the book at [finishedAt] (epoch ms): "Read". [startedAt] is the day they
     * said they started, when they picked one in "Mark as finished": it dates the read's start over the
     * listen-through's. Null — and absent from rows queued before it existed — when they picked none.
     */
    @Serializable
    @SerialName("FINISH")
    data class Finish(
        @SerialName("finishedAt") val finishedAt: Long,
        @SerialName("startedAt") val startedAt: Long? = null,
    ) : HardcoverPushPayload

    /**
     * A read finished in ListenUp before the listener connected (#1540): Read on Hardcover, begun at
     * [startedAt] (epoch ms; null when no listening is recorded for it) and finished at [finishedAt].
     * [readId] is the `book_reads` row, recorded in the history ledger once Hardcover has it.
     */
    @Serializable
    @SerialName("HISTORY")
    data class History(
        @SerialName("startedAt") val startedAt: Long?,
        @SerialName("finishedAt") val finishedAt: Long,
        @SerialName("readId") val readId: String,
    ) : HardcoverPushPayload
}

/** One queued push. [listenThrough] is the listen-through it belongs to (its `started_at`). */
data class HardcoverOutboxRow(
    val id: Long,
    val userId: String,
    val bookId: String,
    val listenThrough: Long,
    val payload: HardcoverPushPayload,
    val attempts: Int,
)

private const val OP_START = "START"
private const val OP_PROGRESS = "PROGRESS"
private const val OP_FINISH = "FINISH"
private const val OP_HISTORY = "HISTORY"

/**
 * `hardcover_outbox`: pushes waiting for Hardcover. One lane per user drains it ([HardcoverPushWorker])
 * in id order, a book's rows strictly in order and different books independently. PROGRESS rows
 * coalesce to the newest position; FINISH supersedes a queued PROGRESS. Nothing here ever drops a row
 * silently — only [complete], [dropListenThrough] (the deletion rule, which spares HISTORY rows), a
 * disconnect, and keeping a book off Hardcover ([HardcoverKeepOff], #1541) remove rows. HISTORY rows ([enqueueHistory]) run after every due live row.
 */
class HardcoverOutbox(
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    private val queries get() = sql.hardcoverOutboxQueries

    /** Queues START for [listenThrough]. */
    suspend fun enqueueStart(
        userId: String,
        bookId: String,
        listenThrough: Long,
        startedAt: Long,
        isReread: Boolean,
    ) {
        insert(
            userId = userId,
            bookId = bookId,
            listenThrough = listenThrough,
            payload = HardcoverPushPayload.Start(startedAt, isReread),
            dueAt = now(),
        )
    }

    /**
     * Queues PROGRESS — or, when one is already queued for the book, folds [positionSeconds] into it,
     * keeping its place and its due time. A new row is not due before [notBefore] (the throttle).
     */
    suspend fun enqueueProgress(
        userId: String,
        bookId: String,
        listenThrough: Long,
        positionSeconds: Long,
        notBefore: Long,
    ) {
        val payload = encode(HardcoverPushPayload.Progress(positionSeconds))
        val createdAt = now()
        suspendTransaction(sql) {
            val folded =
                queries
                    .coalesceProgress(
                        payload = payload,
                        listen_through_started_at = listenThrough,
                        user_id = userId,
                        book_id = bookId,
                    ).value
            if (folded == 0L) {
                queries.insertOp(
                    user_id = userId,
                    book_id = bookId,
                    listen_through_started_at = listenThrough,
                    op = OP_PROGRESS,
                    payload = payload,
                    created_at = createdAt,
                    next_attempt_at = maxOf(notBefore, createdAt),
                )
            }
        }
    }

    /**
     * Queues FINISH for [listenThrough], dropping the book's queued PROGRESS, which it supersedes.
     * [startedAt] is the reader's picked start day, carried on the payload; see [HardcoverPushPayload.Finish].
     */
    suspend fun enqueueFinish(
        userId: String,
        bookId: String,
        listenThrough: Long,
        finishedAt: Long,
        startedAt: Long? = null,
    ) {
        val at = now()
        suspendTransaction(sql) {
            queries.deletePendingProgress(user_id = userId, book_id = bookId)
            queries.insertOp(
                user_id = userId,
                book_id = bookId,
                listen_through_started_at = listenThrough,
                op = OP_FINISH,
                payload = encode(HardcoverPushPayload.Finish(finishedAt, startedAt)),
                created_at = at,
                next_attempt_at = at,
            )
        }
    }

    /** Queues [read] as a HISTORY row (#1540); see [insertHistoryRow]. Internal: [HardcoverHistoryRead] is. */
    internal suspend fun enqueueHistory(
        userId: String,
        read: HardcoverHistoryRead,
    ) {
        val at = now()
        suspendTransaction(sql) { sql.insertHistoryRow(userId, read, at) }
    }

    /** The next row [userId]'s lane may run now, or null. */
    suspend fun head(userId: String): HardcoverOutboxRow? =
        suspendTransaction(sql) { queries.selectHead(user_id = userId, now = now()).executeAsOneOrNull() }?.let { row ->
            HardcoverOutboxRow(
                id = row.id,
                userId = row.user_id,
                bookId = row.book_id,
                listenThrough = row.listen_through_started_at,
                payload = decode(row.payload),
                attempts = row.attempts.toInt(),
            )
        }

    /** When [userId]'s lane next has a row to run (epoch ms), or null when every row is parked or none exist. */
    suspend fun nextWakeAt(userId: String): Long? =
        suspendTransaction(sql) { queries.selectNextWake(userId).executeAsOneOrNull() }

    /** Row [id] reached Hardcover. */
    suspend fun complete(id: Long) {
        suspendTransaction(sql) { queries.deleteById(id) }
    }

    /** Row [id] failed; try again at [nextAttemptAt]. */
    suspend fun reschedule(
        id: Long,
        attempts: Int,
        nextAttemptAt: Long,
        lastError: String,
    ) {
        suspendTransaction(sql) {
            queries.reschedule(
                attempts = attempts.toLong(),
                next_attempt_at = nextAttemptAt,
                last_error = lastError,
                id = id,
            )
        }
    }

    /** The deletion rule: [listenThrough]'s pending rows — its FINISH included — are dropped. */
    suspend fun dropListenThrough(
        userId: String,
        bookId: String,
        listenThrough: Long,
    ) {
        suspendTransaction(sql) {
            queries.deleteListenThrough(user_id = userId, book_id = bookId, listen_through_started_at = listenThrough)
        }
    }

    /** A manual link: [bookId]'s parked rows are due now. */
    suspend fun unpark(
        userId: String,
        bookId: String,
    ) {
        suspendTransaction(sql) { queries.unpark(now = now(), user_id = userId, book_id = bookId) }
    }

    /** Every user with at least one queued row — the lanes to start at boot. */
    suspend fun usersWithPending(): List<String> =
        suspendTransaction(sql) { queries.usersWithPending().executeAsList() }

    /** Every queued row of [userId], in queue order. */
    suspend fun pendingFor(userId: String): List<HardcoverOutboxRow> =
        suspendTransaction(sql) { queries.selectForUser(userId).executeAsList() }.map { row ->
            HardcoverOutboxRow(
                id = row.id,
                userId = row.user_id,
                bookId = row.book_id,
                listenThrough = row.listen_through_started_at,
                payload = decode(row.payload),
                attempts = row.attempts.toInt(),
            )
        }

    private suspend fun insert(
        userId: String,
        bookId: String,
        listenThrough: Long,
        payload: HardcoverPushPayload,
        dueAt: Long,
    ) {
        suspendTransaction(sql) {
            queries.insertOp(
                user_id = userId,
                book_id = bookId,
                listen_through_started_at = listenThrough,
                op = payload.op(),
                payload = encode(payload),
                created_at = dueAt,
                next_attempt_at = dueAt,
            )
        }
    }

    /** How many operations wait for [bookId], parked or due. */
    suspend fun pendingCountFor(
        userId: String,
        bookId: String,
    ): Long = suspendTransaction(sql) { queries.pendingCountForBook(userId, bookId).executeAsOne() }

    private fun now() = clock.now().toEpochMilliseconds()

    private fun encode(payload: HardcoverPushPayload): String = encodePushPayload(payload)

    private fun decode(payload: String): HardcoverPushPayload =
        hardcoverJson.decodeFromString(HardcoverPushPayload.serializer(), payload)

    private fun HardcoverPushPayload.op(): String =
        when (this) {
            is HardcoverPushPayload.Start -> OP_START
            is HardcoverPushPayload.Progress -> OP_PROGRESS
            is HardcoverPushPayload.Finish -> OP_FINISH
            is HardcoverPushPayload.History -> OP_HISTORY
        }
}

/** [payload] as the outbox stores it. */
internal fun encodePushPayload(payload: HardcoverPushPayload): String =
    hardcoverJson.encodeToString(HardcoverPushPayload.serializer(), payload)

/**
 * Queues [read] as a HISTORY row inside the caller's transaction, due at [at]. Its listen-through position
 * is the read's start, or its finish when there is none, so a book's rereads stay distinct and in order.
 * Never coalesced, and spared by the deletion rule.
 */
internal fun ListenUpDatabase.insertHistoryRow(
    userId: String,
    read: HardcoverHistoryRead,
    at: Long,
) {
    hardcoverOutboxQueries.insertHistoryOp(
        user_id = userId,
        book_id = read.bookId,
        listen_through_started_at = read.startedAt ?: read.finishedAt,
        payload = encodePushPayload(HardcoverPushPayload.History(read.startedAt, read.finishedAt, read.readId)),
        created_at = at,
        next_attempt_at = at,
        history_read_id = read.readId,
    )
}
