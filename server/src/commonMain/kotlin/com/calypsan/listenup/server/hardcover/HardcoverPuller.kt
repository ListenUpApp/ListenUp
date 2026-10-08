package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.services.homeTimeZone
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

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
 * with the cursor move ([HardcoverPullStore.commitPage]). Before that commit, [HardcoverWantToRead] brings
 * the page's Want to Read entries onto the user's To Read shelf (#1539); a shelf write that fails fails
 * the page, so the cursor stays put. A read with no finish date never arrives
 * ([HardcoverFinishedRead]), and neither does one finished after today in the user's zone: that date is
 * a typo ListenUp doesn't try to correct, so the read is treated as absent (a full pull removes it if it
 * was pulled before) and arrives on the first full pull once the date is real. The cursor is the last entry's `updated_at` exactly as Hardcover wrote it:
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
    private val wantToRead: HardcoverWantToRead,
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
                .changedSince(
                    accessToken = accessToken,
                    after = state.cursor ?: PULL_EPOCH,
                    afterId = state.cursorId ?: 0L,
                    limit = PULL_PAGE_SIZE,
                ).valueOr { return it }
        if (page.isNotEmpty()) commit(userId, page, now)?.let { return it }
        if (page.size < PULL_PAGE_SIZE) {
            val startedAt = state.fullPullStartedAt
            if (startedAt != null) {
                wantToRead.sweep(userId, startedAt).asPullFailure()?.let { return it }
                store.finishFullPull(userId, startedAt = startedAt, at = now)
            }
            return HardcoverCall.Ok(PullProgress.CAUGHT_UP)
        }
        return HardcoverCall.Ok(PullProgress.MORE_PAGES)
    }

    /** Commits one page, or answers why it can't: a failed shelf write leaves the cursor where it was. */
    private suspend fun commit(
        userId: String,
        page: List<HardcoverShelfEntry>,
        now: Long,
    ): HardcoverCall.Failed? {
        val resolved = resolver.resolve(userId, page)
        wantToRead
            .applyPage(userId = userId, page = page, resolved = resolved, seenAt = now)
            .asPullFailure()
            ?.let { return it }
        val listenUpsOwn = links.pushedReadsAmong(userId, page.flatMap { entry -> entry.finishedReads.map { it.id } })
        val zone = sql.homeTimeZone(userId)
        val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        val books =
            page.mapNotNull { entry ->
                val resolution = resolved[entry.userBookId] ?: return@mapNotNull null
                PulledBook(
                    bookId = resolution.bookId,
                    reads =
                        entry.finishedReads
                            .filterNot { it.id in listenUpsOwn }
                            .mapNotNull { it.toPulled(zone, today) },
                    newLink = (resolution as? ShelfResolution.Matched)?.match,
                )
            }
        val last = page.last()
        store.commitPage(
            userId = userId,
            books = books,
            cursor = last.updatedAt,
            cursorId = last.userBookId,
            seenAt = now,
        )
        return null
    }

    private fun isFullPullDue(
        lastFullPullAt: Long?,
        now: Long,
    ): Boolean = lastFullPullAt == null || now - lastFullPullAt >= FULL_PULL_INTERVAL.inWholeMilliseconds
}

/**
 * Noon on the read's finish date in [zone]; null (and logged) when Hardcover sent a date ListenUp can't
 * read, or one after [today] — a finish that hasn't happened yet is nonsense, not ours to fix.
 */
private fun HardcoverFinishedRead.toPulled(
    zone: TimeZone,
    today: LocalDate,
): PulledRead? {
    val date =
        try {
            LocalDate.parse(finishedOn.take(ISO_DATE_LENGTH))
        } catch (_: IllegalArgumentException) {
            log.warn { "hardcover pull: read $id has an unreadable finish date '$finishedOn'; skipped" }
            return null
        }
    if (date > today) {
        log.info { "hardcover pull: read $id finishes on $date, after today ($today); skipped until then" }
        return null
    }
    return PulledRead(id, date.atTime(PULLED_READ_HOUR, 0).toInstant(zone).toEpochMilliseconds())
}

/**
 * A shelf write that failed, as the pull's own failure: nothing moves the cursor, and the pull's retry
 * policy asks for the page again. Null when it worked.
 */
internal fun AppResult<Unit>.asPullFailure(): HardcoverCall.Failed? =
    (this as? AppResult.Failure)?.let { failure ->
        HardcoverCall.Failed("want to read: ${failure.error.code} ${failure.error.debugInfo.orEmpty()}".trim())
    }
