package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.services.homeTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

private val log = loggerFor<HardcoverPushExecutor>()

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
 * adopts the read the lost call created instead of opening a second one.
 */
class HardcoverPushExecutor(
    private val userBooks: HardcoverUserBooks,
    private val links: HardcoverBookLinkStore,
    private val outbox: HardcoverOutbox,
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    /** Runs [row] for LINKED [link] with [accessToken]. */
    suspend fun execute(
        row: HardcoverOutboxRow,
        link: HardcoverBookLink,
        accessToken: String,
    ): PushOutcome {
        val hcBookId = checkNotNull(link.hcBookId) { "only a LINKED book is pushed" }
        if (link.suppressedListenThrough == row.listenThrough) return suppress(row)
        val shelf = userBooks.userBookFor(accessToken, hcBookId).valueOr { return PushOutcome.Failed(it) }
        if (deletedOnHardcover(link, row.listenThrough, shelf)) return suppress(row)
        val zone = sql.homeTimeZone(row.userId)
        val target = Target(row, link, hcBookId, shelf, accessToken, zone)
        return when (val payload = row.payload) {
            is HardcoverPushPayload.Start -> target.start(payload)
            is HardcoverPushPayload.Progress -> target.progress(payload)
            is HardcoverPushPayload.Finish -> target.finish(payload)
        }
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

        suspend fun finish(payload: HardcoverPushPayload.Finish): PushOutcome {
            val open = openRead(startedAt = listenThroughStart).valueOr { return PushOutcome.Failed(it) }
            userBooks
                .updateRead(token, open.read.copy(finishedAt = dateOf(payload.finishedAt).toString()))
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
            return PushOutcome.Done
        }

        /**
         * The read this listen-through writes to: the one already recorded for it; else an unfinished
         * read on the shelf (continued); else a new one dated [startedAt] — shelving the book at the
         * matched edition as Reading first when it isn't on the shelf. Recorded on the link and in the
         * pushed-read ledger. A recorded read is always on [shelf]: [deletedOnHardcover] suppressed the
         * row otherwise.
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
                    ?: userBooks
                        .createUserBook(
                            token,
                            hcBookId,
                            link.hcEditionId,
                            HardcoverStatus.READING,
                        ).valueOr { return it }
            val read =
                shelf?.openRead
                    ?: run {
                        val startedOn = startedAt?.let(::dateOf)
                        val readId =
                            userBooks
                                .openRead(
                                    token,
                                    userBookId,
                                    startedOn,
                                    link.hcEditionId,
                                ).valueOr { return it }
                        HardcoverRead(
                            readId,
                            startedOn?.toString(),
                            finishedAt = null,
                            progressSeconds = null,
                            editionId = link.hcEditionId,
                        )
                    }
            links.recordOpenRead(row.userId, row.bookId, userBookId, read.id, row.listenThrough)
            links.recordPushedRead(row.userId, read.id, row.bookId)
            return HardcoverCall.Ok(OpenRead(userBookId, read, opened = true))
        }

        private fun dateOf(epochMs: Long): LocalDate = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date
    }

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
