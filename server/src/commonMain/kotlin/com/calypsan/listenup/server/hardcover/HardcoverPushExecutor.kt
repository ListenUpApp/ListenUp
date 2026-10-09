package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.services.homeTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val log = loggerFor<HardcoverPushExecutor>()

/**
 * How long Hardcover may take to show what ListenUp just wrote. Its answers lag its writes: on
 * 2026-10-07 a shelf entry and a read ListenUp had written were both missing from the next answers,
 * 174 ms apart, which opened a second read and took the read's absence for the user deleting it.
 * Within this window a missing record is lag — the row fails and the worker retries it — never proof.
 */
private val HARDCOVER_LAG_GRACE: Duration = 10.minutes

private val NOT_VISIBLE_YET = HardcoverCall.Failed("Hardcover doesn't show ListenUp's latest change yet")

/** What running one outbox row came to. */
sealed interface PushOutcome {
    /** Hardcover has it: the row is done. */
    data object Done : PushOutcome

    /** The deletion rule fired: this listen-through's rows are gone, deliberately. */
    data object Suppressed : PushOutcome

    /** Hardcover didn't take it; the worker's error policy decides what happens next. */
    data class Failed(
        val failure: HardcoverCall<Nothing>,
    ) : PushOutcome
}

/**
 * The Hardcover shelf entry and read one listen-through writes to, with the read as Hardcover now
 * holds it — every update sends a copy of [read], never a lone field. [opened] is true when this call
 * found or opened it.
 */
private data class OpenRead(
    val userBookId: Long,
    val read: HardcoverRead,
    val opened: Boolean,
)

/**
 * Runs one outbox row against Hardcover (spec B2, "Which read an operation targets" and "The deletion
 * rule"). Each attempt starts from Hardcover's current shelf entry, so a retry after a lost answer
 * adopts the read the lost call created instead of opening a second one. A HISTORY row (#1540) goes to
 * [HardcoverHistoryPush], which paces each call on the shared rate limiter. A FINISH that lands records
 * its read in the history ledger.
 */
class HardcoverPushExecutor(
    private val userBooks: HardcoverUserBooks,
    private val links: HardcoverBookLinkStore,
    private val outbox: HardcoverOutbox,
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    rateLimiter: HardcoverRateLimiter = HardcoverRateLimiter(),
) {
    private val history =
        HardcoverHistoryPush(
            userBooks = userBooks,
            links = links,
            sql = sql,
            clock = clock,
            rateLimiter = rateLimiter,
        )

    /** Runs [row] for LINKED [link] with [accessToken]. */
    suspend fun execute(
        row: HardcoverOutboxRow,
        link: HardcoverBookLink,
        accessToken: String,
    ): PushOutcome {
        val hcBookId = checkNotNull(link.hcBookId) { "only a LINKED book is pushed" }
        return when (val payload = row.payload) {
            // A finished read of its own (#1540): no listen-through to continue, so the deletion rule has nothing to act on.
            is HardcoverPushPayload.History -> {
                history.send(row = row, payload = payload, link = link, hcBookId = hcBookId, token = accessToken)
            }

            is HardcoverPushPayload.Start -> {
                live(row = row, link = link, hcBookId = hcBookId, accessToken = accessToken) { start(payload) }
            }

            is HardcoverPushPayload.Progress -> {
                live(row = row, link = link, hcBookId = hcBookId, accessToken = accessToken) { progress(payload) }
            }

            is HardcoverPushPayload.Finish -> {
                live(row = row, link = link, hcBookId = hcBookId, accessToken = accessToken) { finish(payload) }
            }
        }
    }

    /** Runs one listen-through's [operation] from Hardcover's current shelf entry, unless the deletion rule silences it. */
    private suspend fun live(
        row: HardcoverOutboxRow,
        link: HardcoverBookLink,
        hcBookId: Long,
        accessToken: String,
        operation: suspend Target.() -> PushOutcome,
    ): PushOutcome {
        if (link.suppressedListenThrough == row.listenThrough) return suppress(row)
        val shelf = userBooks.userBookFor(accessToken, hcBookId).valueOr { return PushOutcome.Failed(it) }
        if (deletedOnHardcover(link, row.listenThrough, shelf)) {
            return if (writtenWithinLag(link)) PushOutcome.Failed(NOT_VISIBLE_YET) else suppress(row)
        }
        val zone = sql.homeTimeZone(row.userId)
        val target =
            Target(row = row, link = link, hcBookId = hcBookId, shelf = shelf, token = accessToken, zone = zone)
        return target.operation()
    }

    /** Everything one row's operation needs, so the three operations read as the spec does. */
    private inner class Target(
        val row: HardcoverOutboxRow,
        val link: HardcoverBookLink,
        val hcBookId: Long,
        val shelf: HardcoverUserBook?,
        val token: String,
        val zone: TimeZone,
    ) {
        private val listenThroughStart: Long? = row.listenThrough.takeIf { it != LEGACY_LISTEN_THROUGH }

        suspend fun start(payload: HardcoverPushPayload.Start): PushOutcome {
            val open = openRead(startedAt = payload.startedAt).valueOr { return PushOutcome.Failed(it) }
            if (shelf != null && shelf.statusId != HardcoverStatus.READING) {
                userBooks
                    .setStatus(
                        token,
                        open.userBookId,
                        HardcoverStatus.READING,
                    ).valueOr { return PushOutcome.Failed(it) }
            }
            return PushOutcome.Done
        }

        suspend fun progress(payload: HardcoverPushPayload.Progress): PushOutcome {
            val open = openRead(startedAt = listenThroughStart).valueOr { return PushOutcome.Failed(it) }
            if (open.opened && shelf != null && shelf.statusId != HardcoverStatus.READING) {
                userBooks
                    .setStatus(
                        token,
                        open.userBookId,
                        HardcoverStatus.READING,
                    ).valueOr { return PushOutcome.Failed(it) }
            }
            userBooks
                .updateRead(token, open.read.copy(progressSeconds = payload.positionSeconds))
                .valueOr { return PushOutcome.Failed(it) }
            links.markProgressPushed(row.userId, row.bookId, clock.now().toEpochMilliseconds())
            return PushOutcome.Done
        }

        /**
         * Finishes the read. A start the reader picked ([HardcoverPushPayload.Finish.startedAt]) dates it,
         * over whatever start the read already has; without one the read keeps the start it was opened
         * with — the listen-through's, or Hardcover's own.
         */
        suspend fun finish(payload: HardcoverPushPayload.Finish): PushOutcome {
            val open =
                openRead(startedAt = payload.startedAt ?: listenThroughStart).valueOr { return PushOutcome.Failed(it) }
            val finished =
                open.read.copy(
                    startedAt = payload.startedAt?.let { dateOf(it).toString() } ?: open.read.startedAt,
                    finishedAt = dateOf(payload.finishedAt).toString(),
                )
            userBooks
                .updateRead(token, finished)
                .valueOr { return PushOutcome.Failed(it) }
            if (shelf == null || shelf.statusId != HardcoverStatus.READ) {
                userBooks
                    .setStatus(
                        token,
                        open.userBookId,
                        HardcoverStatus.READ,
                    ).valueOr { return PushOutcome.Failed(it) }
            }
            links.clearOpenRead(row.userId, row.bookId)
            // The read is on Hardcover now: it is never history to offer again, even after a reconnect (#1540).
            sql.recordLiveFinish(
                userId = row.userId,
                bookId = row.bookId,
                listenThrough = row.listenThrough,
                at = clock.now().toEpochMilliseconds(),
            )
            return PushOutcome.Done
        }

        /**
         * The read this listen-through writes to: the one already recorded for it; else an unfinished
         * read on the shelf (continued); else, for a book not yet on the shelf, it is shelved at the
         * matched edition as Reading and the read Hardcover opens for that is adopted; else a new one
         * dated [startedAt]. A shelf entry this listen-through created but never heard back about
         * ([HardcoverBookLink.isShelvingFor]) is treated as just shelved, so its read is redated too.
         * Recorded on the link and in the pushed-read ledger. A recorded read is always on [shelf]:
         * [deletedOnHardcover] suppressed the row otherwise.
         */
        private suspend fun openRead(startedAt: Long?): HardcoverCall<OpenRead> {
            val recordedShelf = link.hcUserBookId
            if (link.openReadListenThrough == row.listenThrough && recordedShelf != null) {
                shelf?.reads?.firstOrNull { it.id == link.openHcReadId }?.let { recorded ->
                    return HardcoverCall.Ok(OpenRead(recordedShelf, recorded, opened = false))
                }
            }
            val userBookId =
                shelf?.id
                    ?: run {
                        if (link.isShelvingFor(row.listenThrough) && writtenWithinLag(link)) return NOT_VISIBLE_YET
                        links.markShelving(row.userId, row.bookId, row.listenThrough)
                        userBooks
                            .createUserBook(
                                accessToken = token,
                                hcBookId = hcBookId,
                                hcEditionId = link.hcEditionId,
                                statusId = HardcoverStatus.READING,
                            ).valueOr { return it }
                    }
            val shelvedJustNow = shelf == null || link.isShelvingFor(row.listenThrough)
            val read =
                (if (shelvedJustNow) adoptReadHardcoverOpened(startedAt).valueOr { return it } else null)
                    ?: shelf?.openRead
                    ?: openNewRead(userBookId, startedAt).valueOr { return it }
            links.recordOpenRead(
                userId = row.userId,
                bookId = row.bookId,
                userBookId = userBookId,
                readId = read.id,
                listenThrough = row.listenThrough,
            )
            links.recordPushedRead(row.userId, read.id, row.bookId)
            return HardcoverCall.Ok(OpenRead(userBookId, read, opened = true))
        }

        /** Opens a new read on shelf entry [userBookId], dated [startedAt] when it is known. */
        private suspend fun openNewRead(
            userBookId: Long,
            startedAt: Long?,
        ): HardcoverCall<HardcoverRead> {
            val startedOn = startedAt?.let(::dateOf)
            val readId =
                userBooks
                    .openRead(
                        accessToken = token,
                        userBookId = userBookId,
                        startedAt = startedOn,
                        hcEditionId = link.hcEditionId,
                    ).valueOr { return it }
            return HardcoverCall.Ok(
                HardcoverRead(
                    id = readId,
                    startedAt = startedOn?.toString(),
                    finishedAt = null,
                    progressSeconds = null,
                    editionId = link.hcEditionId,
                ),
            )
        }

        /**
         * Hardcover opens a read by itself, dated today, when a book is shelved as Currently Reading
         * (seen live, 2026-09-30). Opening another would leave a stray open read beside ListenUp's, so
         * the one it opened is adopted and moved to [startedAt] — kept at Hardcover's date when unknown.
         * `Ok(null)` when Hardcover opened none. A shelf entry ListenUp just made that Hardcover doesn't
         * show yet fails the row: opening a read now would sit beside the one Hardcover opened.
         */
        private suspend fun adoptReadHardcoverOpened(startedAt: Long?): HardcoverCall<HardcoverRead?> {
            val shelved = userBooks.userBookFor(token, hcBookId).valueOr { return it } ?: return NOT_VISIBLE_YET
            val opened = shelved.openRead ?: return HardcoverCall.Ok(null)
            val dated =
                opened.copy(
                    startedAt = startedAt?.let { dateOf(it).toString() } ?: opened.startedAt,
                    editionId = link.hcEditionId ?: opened.editionId,
                )
            if (dated != opened) userBooks.updateRead(token, dated).valueOr { return it }
            return HardcoverCall.Ok(dated)
        }

        private fun dateOf(epochMs: Long): LocalDate = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
    }

    /** ListenUp changed [link] within [HARDCOVER_LAG_GRACE]: Hardcover may not show that change yet. */
    private fun writtenWithinLag(link: HardcoverBookLink): Boolean =
        clock.now().toEpochMilliseconds() - link.changedAt < HARDCOVER_LAG_GRACE.inWholeMilliseconds

    /**
     * The user deleted ListenUp's record on Hardcover: the shelf entry, or the read this listen-through
     * was writing to. Only the listen-through that recorded the read can find it missing — a new one
     * starts afresh.
     */
    private fun deletedOnHardcover(
        link: HardcoverBookLink,
        listenThrough: Long,
        shelf: HardcoverUserBook?,
    ): Boolean {
        val recordedRead = link.openHcReadId ?: return false
        if (link.openReadListenThrough != listenThrough) return false
        return shelf == null || shelf.id != link.hcUserBookId || shelf.reads.none { it.id == recordedRead }
    }

    private suspend fun suppress(row: HardcoverOutboxRow): PushOutcome {
        log.info {
            "hardcover: user=${row.userId} book=${row.bookId} deleted on Hardcover; listen-through ${row.listenThrough} goes quiet"
        }
        links.suppress(row.userId, row.bookId, row.listenThrough)
        outbox.dropListenThrough(row.userId, row.bookId, row.listenThrough)
        return PushOutcome.Suppressed
    }
}
