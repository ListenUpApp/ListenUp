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
 * - Currently Reading or Want to Read: the open read — the one there before, or the one Hardcover opened
 *   for shelving — is finished and redated when Hardcover's start is later or empty; with none open, one is
 *   added whole. Only then is the book set to Read, because Hardcover finishes any read still open on that
 *   change itself, dated today (seen live, 2026-10-01); a read Hardcover makes of its own for the change is
 *   removed.
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
                    sql.ownReadsThrough(
                        userId = row.userId,
                        bookId = row.bookId,
                        finishedAt = payload.finishedAt,
                        readId = payload.readId,
                    ) -> {
                    alreadyThere(
                        row = row,
                        hcBookId = hcBookId,
                        shelf = shelf,
                        finishedOn = dates.finished,
                        token = token,
                    )
                }

                else -> {
                    addRead(
                        row = row,
                        link = link,
                        hcBookId = hcBookId,
                        shelf = shelf,
                        dates = dates,
                        token = token,
                    )
                }
            }.valueOr { return PushOutcome.Failed(it) }
        sql.recordHardcoverHistoryRead(
            userId = row.userId,
            readId = payload.readId,
            outcome = outcome,
            at = clock.now().toEpochMilliseconds(),
        )
        return PushOutcome.Done
    }

    /** Shelves the book as Currently Reading — which opens a read ListenUp then dates — and reads the entry back. */
    private suspend fun shelve(
        token: String,
        hcBookId: Long,
        hcEditionId: Long?,
    ): HardcoverCall<HardcoverUserBook> {
        paced {
            userBooks.createUserBook(
                accessToken = token,
                hcBookId = hcBookId,
                hcEditionId = hcEditionId,
                statusId = HardcoverStatus.READING,
            )
        }.valueOr { return it }
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
        hcBookId: Long,
        shelf: HardcoverUserBook,
        finishedOn: LocalDate,
        token: String,
    ): HardcoverCall<HardcoverHistoryOutcome> {
        val ours =
            shelf.reads.firstOrNull { it.finishedAt == finishedOn.toString() && links.isPushedRead(row.userId, it.id) }
                ?: return HardcoverCall.Ok(HardcoverHistoryOutcome.ALREADY_THERE)
        if (shelf.statusId != HardcoverStatus.READ) {
            markRead(token = token, hcBookId = hcBookId, shelf = shelf, ours = ours.id).valueOr { return it }
        }
        return HardcoverCall.Ok(HardcoverHistoryOutcome.SENT)
    }

    /**
     * Writes the read first, then makes the book Read. Hardcover finishes any unfinished read by itself, on
     * today, when a book becomes Read (seen live, 2026-10-01), so a read still open at the status change would
     * come back finished on the wrong day. The open read — the one there before, or the one Hardcover opened
     * for shelving — is finished with this read's dates; with none open, the read is added whole. Only then
     * does the status change, and any read Hardcover made of its own for it is removed: one read remains.
     */
    private suspend fun addRead(
        row: HardcoverOutboxRow,
        link: HardcoverBookLink,
        hcBookId: Long,
        shelf: HardcoverUserBook,
        dates: ReadDates,
        token: String,
    ): HardcoverCall<HardcoverHistoryOutcome> {
        val open = shelf.openRead
        val ours =
            if (open != null) {
                links.recordPushedRead(userId = row.userId, readId = open.id, bookId = row.bookId, historic = true)
                val finishedRead =
                    open.copy(
                        startedAt = startFor(open.startedAt, dates),
                        finishedAt = dates.finished.toString(),
                        editionId = open.editionId ?: link.hcEditionId,
                    )
                paced { userBooks.updateRead(token, finishedRead) }.valueOr { return it }
                open.id
            } else {
                val readId =
                    paced {
                        userBooks.openRead(
                            accessToken = token,
                            userBookId = shelf.id,
                            startedAt = dates.started,
                            hcEditionId = link.hcEditionId,
                            finishedAt = dates.finished,
                        )
                    }.valueOr { return it }
                links.recordPushedRead(userId = row.userId, readId = readId, bookId = row.bookId, historic = true)
                readId
            }
        if (shelf.statusId != HardcoverStatus.READ) {
            markRead(token = token, hcBookId = hcBookId, shelf = shelf, ours = ours).valueOr { return it }
        }
        return HardcoverCall.Ok(HardcoverHistoryOutcome.SENT)
    }

    /**
     * Makes [shelf] Read, then reads it back and removes every read Hardcover made of its own for the change —
     * one that was neither on [shelf] before nor [ours], ListenUp's read — so this send adds one read alone.
     */
    private suspend fun markRead(
        token: String,
        hcBookId: Long,
        shelf: HardcoverUserBook,
        ours: Long,
    ): HardcoverCall<Unit> {
        val before = shelf.reads.mapTo(mutableSetOf(ours)) { it.id }
        paced { userBooks.setStatus(token, shelf.id, HardcoverStatus.READ) }.valueOr { return it }
        readBack(token, hcBookId)
            .valueOr { return it }
            .reads
            .filter { it.id !in before }
            .forEach { stray -> paced { userBooks.deleteRead(token, stray.id) }.valueOr { return it } }
        return HardcoverCall.Ok(Unit)
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
