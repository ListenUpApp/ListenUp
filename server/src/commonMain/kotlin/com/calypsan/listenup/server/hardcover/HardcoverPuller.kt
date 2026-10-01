package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.services.homeTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

private val log = loggerFor<HardcoverPuller>()

/** Shelf entries per page. */
internal const val PULL_PAGE_SIZE = 25

/** Where a first or full pull starts: before any shelf entry could have changed. */
internal const val PULL_EPOCH = "1970-01-01T00:00:00+00:00"

/** How often the pull re-reads the whole shelf — how a shelf entry deleted on Hardcover is noticed. */
internal val FULL_PULL_INTERVAL: Duration = 24.hours

/** A pulled read's date lands at noon, local time, so it reads as that date in any nearby zone. */
private const val PULLED_READ_HOUR = 12
private const val ISO_DATE_LENGTH = 10

/** How far one [HardcoverPuller.pullPage] got. */
enum class PullProgress {
    /** A full page arrived: there may be more. */
    MORE_PAGES,

    /** The pull reached the end of what changed. */
    CAUGHT_UP,
}

/**
 * One page of the pull (spec B3). Asks Hardcover for the user's shelf entries changed since the
 * stored cursor, resolves them to library books ([HardcoverShelfResolver]), drops every read ListenUp
 * opened or continued (the pushed-read ledger — the echo suppression, which wins even when the user
 * finished that read on Hardcover), and commits the rest as `source = 'hardcover'` reads together
 * with the cursor move ([HardcoverPullStore.commitPage]). A read with no finish date never arrives
 * ([HardcoverFinishedRead]). The cursor is the last entry's `updated_at` exactly as Hardcover wrote it:
 * only Hardcover orders and compares it.
 *
 * Every [FULL_PULL_INTERVAL] (or when asked, see [HardcoverPullStore.requestFullPull]) the pull
 * starts from the beginning of the shelf, and the full pull's completion deletes the pulled reads of
 * entries it never saw. Pulled reads go straight to the store: never through `StatsRecorder` or the
 * outbox, so they raise no stats event, no feed item and no push.
 */
class HardcoverPuller(
    private val userBooks: HardcoverUserBooks,
    private val store: HardcoverPullStore,
    private val resolver: HardcoverShelfResolver,
    private val links: HardcoverBookLinkStore,
    private val rateLimiter: HardcoverRateLimiter,
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    /** Pulls [userId]'s next page with [accessToken]. A failure changes nothing, so the page is simply asked again. */
    suspend fun pullPage(
        userId: String,
        accessToken: String,
    ): HardcoverCall<PullProgress> {
        val saved = store.pullState(userId) ?: return HardcoverCall.Ok(PullProgress.CAUGHT_UP)
        val now = clock.now().toEpochMilliseconds()
        val state =
            if (saved.fullPullStartedAt == null && isFullPullDue(saved.lastFullPullAt, now)) {
                store.startFullPull(userId, now)
                HardcoverPullState(
                    cursor = null,
                    cursorId = null,
                    fullPullStartedAt = now,
                    lastFullPullAt = saved.lastFullPullAt,
                )
            } else {
                saved
            }
        rateLimiter.await()
        val page =
            userBooks
                .changedSince(accessToken, state.cursor ?: PULL_EPOCH, state.cursorId ?: 0L, PULL_PAGE_SIZE)
                .valueOr { return it }
        if (page.isNotEmpty()) commit(userId, page, now)
        if (page.size < PULL_PAGE_SIZE) {
            state.fullPullStartedAt?.let { store.finishFullPull(userId, startedAt = it, at = now) }
            return HardcoverCall.Ok(PullProgress.CAUGHT_UP)
        }
        return HardcoverCall.Ok(PullProgress.MORE_PAGES)
    }

    private suspend fun commit(
        userId: String,
        page: List<HardcoverShelfEntry>,
        now: Long,
    ) {
        val resolved = resolver.resolve(userId, page)
        val listenUpsOwn = links.pushedReadsAmong(userId, page.flatMap { entry -> entry.finishedReads.map { it.id } })
        val zone = sql.homeTimeZone(userId)
        val books =
            page.mapNotNull { entry ->
                val resolution = resolved[entry.userBookId] ?: return@mapNotNull null
                PulledBook(
                    bookId = resolution.bookId,
                    reads = entry.finishedReads.filterNot { it.id in listenUpsOwn }.mapNotNull { it.toPulled(zone) },
                    newLink = (resolution as? ShelfResolution.Matched)?.match,
                )
            }
        val last = page.last()
        store.commitPage(userId, books, cursor = last.updatedAt, cursorId = last.userBookId, seenAt = now)
    }

    private fun isFullPullDue(
        lastFullPullAt: Long?,
        now: Long,
    ): Boolean = lastFullPullAt == null || now - lastFullPullAt >= FULL_PULL_INTERVAL.inWholeMilliseconds
}

/** Noon on the read's finish date in [zone]; null (and logged) when Hardcover sent a date ListenUp can't read. */
private fun HardcoverFinishedRead.toPulled(zone: TimeZone): PulledRead? {
    val date =
        try {
            LocalDate.parse(finishedOn.take(ISO_DATE_LENGTH))
        } catch (_: IllegalArgumentException) {
            log.warn { "hardcover pull: read $id has an unreadable finish date '$finishedOn'; skipped" }
            return null
        }
    return PulledRead(id, date.atTime(PULLED_READ_HOUR, 0).toInstant(zone).toEpochMilliseconds())
}
