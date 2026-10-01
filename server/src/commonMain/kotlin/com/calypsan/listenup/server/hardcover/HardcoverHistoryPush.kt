package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.homeTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** A history read's calendar dates in the listener's zone: [started] when known, and [finished]. */
private data class ReadDates(
    val started: LocalDate?,
    val finished: LocalDate,
)

/** The statuses a history read may change: everything else (Did Not Finish, Paused, …) is Hardcover's word. */
private val SENDABLE_STATUSES = setOf(HardcoverStatus.WANT_TO_READ, HardcoverStatus.READING, HardcoverStatus.READ)

/**
 * Sends one HISTORY row (#1540): a read finished in ListenUp before the listener connected, arriving on
 * Hardcover as Read with its own dates — filling in what Hardcover holds, never duplicating it.
 *
 * - Not on the shelf: shelved as Currently Reading (Hardcover opens a read for that), then handled as below.
 * - Currently Reading or Want to Read: set to Read, read back, and the open read — the one there before, or
 *   one Hardcover opened for the change — is finished and redated when Hardcover's start is later or empty;
 *   with none open, one is added whole.
 * - Read: skipped when Hardcover holds as many reads as ListenUp has of the book up to and including this
 *   one — not counting reads ListenUp pushed live, and counting a Read entry with no read at all as one;
 *   otherwise one read is added.
 * - Any other status (Did Not Finish, Paused, …): left as Hardcover has it.
 *
 * Every attempt starts from Hardcover's current entry, so a retry after a lost answer meets what the lost
 * call did. A read ListenUp finishes or adds goes in the pushed-read ledger as a HISTORY read, so the pull
 * never mirrors it back and a retry recognises it. Each Hardcover call waits on the shared
 * [HardcoverRateLimiter]. The deletion rule never applies — a HISTORY row is no listen-through. The outcome
 * goes in the history ledger.
 */
internal class HardcoverHistoryPush(
    private val userBooks: HardcoverUserBooks,
    private val links: HardcoverBookLinkStore,
    private val sql: ListenUpDatabase,
    private val clock: Clock,
    private val rateLimiter: HardcoverRateLimiter,
) {
    suspend fun send(
        row: HardcoverOutboxRow,
        payload: HardcoverPushPayload.History,
        link: HardcoverBookLink,
        hcBookId: Long,
        token: String,
    ): PushOutcome {
        val zone = sql.homeTimeZone(row.userId)
        val dates = ReadDates(payload.startedAt?.let { dateOf(it, zone) }, dateOf(payload.finishedAt, zone))
        val shelf =
            paced { userBooks.userBookFor(token, hcBookId) }.valueOr { return PushOutcome.Failed(it) }
                ?: shelve(token, hcBookId, link.hcEditionId).valueOr { return PushOutcome.Failed(it) }
        val outcome =
            when {
                shelf.statusId !in SENDABLE_STATUSES -> {
                    HardcoverCall.Ok(HardcoverHistoryOutcome.ALREADY_THERE)
                }

                hardcoverReads(row, shelf) >=
                    sql.ownReadsThrough(row.userId, row.bookId, payload.finishedAt, payload.readId) -> {
                    alreadyThere(row, shelf, dates.finished, token)
                }

                else -> {
                    addRead(row, link, hcBookId, shelf, dates, token)
                }
            }.valueOr { return PushOutcome.Failed(it) }
        sql.recordHardcoverHistoryRead(row.userId, payload.readId, outcome, clock.now().toEpochMilliseconds())
        return PushOutcome.Done
    }

    /** Shelves the book as Currently Reading — which opens a read ListenUp then dates — and reads the entry back. */
    private suspend fun shelve(
        token: String,
        hcBookId: Long,
        hcEditionId: Long?,
    ): HardcoverCall<HardcoverUserBook> {
        paced { userBooks.createUserBook(token, hcBookId, hcEditionId, HardcoverStatus.READING) }.valueOr { return it }
        return readBack(token, hcBookId)
    }

    /**
     * The reads on [shelf] that count against ListenUp's history: its finished reads less those ListenUp pushed
     * live (a reread after connecting is no earlier read) — or one for a Read entry holding no read at all.
     */
    private suspend fun hardcoverReads(
        row: HardcoverOutboxRow,
        shelf: HardcoverUserBook,
    ): Long {
        val finished = shelf.reads.filter { it.finishedAt != null }
        if (finished.isEmpty()) return if (shelf.statusId == HardcoverStatus.READ && shelf.openRead == null) 1L else 0L
        return finished.count { !links.isLivePushedRead(row.userId, it.id) }.toLong()
    }

    /**
     * Hardcover already holds enough reads: nothing is added. When one of them is ListenUp's own, finished on
     * this read's day by an attempt whose answer was lost, the book is still made Read and the read counts as
     * sent.
     */
    private suspend fun alreadyThere(
        row: HardcoverOutboxRow,
        shelf: HardcoverUserBook,
        finishedOn: LocalDate,
        token: String,
    ): HardcoverCall<HardcoverHistoryOutcome> {
        val ours =
            shelf.reads.any { it.finishedAt == finishedOn.toString() && links.isPushedRead(row.userId, it.id) }
        if (!ours) return HardcoverCall.Ok(HardcoverHistoryOutcome.ALREADY_THERE)
        if (shelf.statusId != HardcoverStatus.READ) {
            paced { userBooks.setStatus(token, shelf.id, HardcoverStatus.READ) }.valueOr { return it }
        }
        return HardcoverCall.Ok(HardcoverHistoryOutcome.SENT)
    }

    /**
     * Makes the book Read first, then reads it back: Hardcover may open a read of its own on a status change,
     * and one it opened is adopted, never doubled. The open read is finished with this read's dates; with
     * none open, the read is added whole.
     */
    private suspend fun addRead(
        row: HardcoverOutboxRow,
        link: HardcoverBookLink,
        hcBookId: Long,
        shelf: HardcoverUserBook,
        dates: ReadDates,
        token: String,
    ): HardcoverCall<HardcoverHistoryOutcome> {
        val current =
            if (shelf.statusId == HardcoverStatus.READ) {
                shelf
            } else {
                paced { userBooks.setStatus(token, shelf.id, HardcoverStatus.READ) }.valueOr { return it }
                readBack(token, hcBookId).valueOr { return it }
            }
        val open = current.openRead
        if (open != null) {
            links.recordPushedRead(row.userId, open.id, row.bookId, historic = true)
            val finishedRead =
                open.copy(
                    startedAt = startFor(open.startedAt, dates),
                    finishedAt = dates.finished.toString(),
                    editionId = open.editionId ?: link.hcEditionId,
                )
            paced { userBooks.updateRead(token, finishedRead) }.valueOr { return it }
        } else {
            val readId =
                paced {
                    userBooks.openRead(token, current.id, dates.started, link.hcEditionId, finishedAt = dates.finished)
                }.valueOr { return it }
            links.recordPushedRead(row.userId, readId, row.bookId, historic = true)
        }
        return HardcoverCall.Ok(HardcoverHistoryOutcome.SENT)
    }

    private suspend fun readBack(
        token: String,
        hcBookId: Long,
    ): HardcoverCall<HardcoverUserBook> {
        val entry = paced { userBooks.userBookFor(token, hcBookId) }.valueOr { return it }
        return entry?.let { HardcoverCall.Ok(it) }
            ?: HardcoverCall.Failed("history: book $hcBookId is not on the shelf")
    }

    /**
     * The start a finished read carries: ListenUp's, when Hardcover's is empty or later. With none of its
     * own, Hardcover's is kept — unless it falls after the finish (a read Hardcover opened just now, dated
     * today), when the read starts on its finish day: better a start ListenUp can't vouch for than one after
     * the end.
     */
    private fun startFor(
        hardcoverStart: String?,
        dates: ReadDates,
    ): String? {
        val ours = dates.started?.toString()
        val finished = dates.finished.toString()
        return when {
            ours != null && (hardcoverStart == null || hardcoverStart > ours) -> ours
            ours == null && hardcoverStart != null && hardcoverStart > finished -> finished
            else -> hardcoverStart
        }
    }

    private suspend inline fun <T> paced(call: () -> HardcoverCall<T>): HardcoverCall<T> {
        rateLimiter.await()
        return call()
    }

    private fun dateOf(
        epochMs: Long,
        zone: TimeZone,
    ): LocalDate = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
}
