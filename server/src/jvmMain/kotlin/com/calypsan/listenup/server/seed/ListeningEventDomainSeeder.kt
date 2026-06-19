@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ListeningEventTable
import com.calypsan.listenup.server.db.UserTable
import com.calypsan.listenup.server.services.ListeningEventRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

/** How many demo books to seed listening events for (at most). */
private const val DEMO_BOOK_COUNT = 3

/** Milliseconds in one minute. */
private const val ONE_MINUTE_MS = 60_000L

/** Milliseconds in one day. */
private const val ONE_DAY_MS = 86_400_000L

/**
 * Seeds demo listening events for the [UserDomainSeeder.DEMO_EMAIL] account,
 * distributed across the last 7 days so the materialized `user_stats` row
 * (populated by `UserStatsUpdater` inside each [ListeningEventRepository.upsert]
 * call) reflects a non-trivial recent listening pattern:
 * - `totalSecondsLast7Days` becomes non-zero
 * - `currentStreakDays` shows realistic multi-day progression
 *
 * Runs after [PlaybackPositionDomainSeeder] (order 10). If no books have been
 * scanned yet, seeding is deferred to the next restart — same graceful-skip
 * pattern as [PlaybackPositionDomainSeeder].
 *
 * Writes through [ListeningEventRepository.upsert] — the real domain write-path
 * — so seeded rows are indistinguishable from real ones and `user_stats` is
 * populated automatically.
 */
internal class ListeningEventDomainSeeder(
    private val db: Database,
    private val listeningEventRepository: ListeningEventRepository,
    private val clock: Clock = Clock.System,
) : DomainSeeder {
    override val domainName: String = "listening_events"

    /**
     * Runs after [PlaybackPositionDomainSeeder] (order 10). Listening events have
     * a foreign-key dependency on books, so the scanner needs to have written at
     * least one book first.
     */
    override val order: Int = 20

    override suspend fun isAlreadySeeded(): Boolean {
        val userId = demoUserId() ?: return false
        return suspendTransaction(db) {
            ListeningEventTable
                .selectAll()
                .where { (ListeningEventTable.userId eq userId) and ListeningEventTable.deletedAt.isNull() }
                .limit(1)
                .any()
        }
    }

    override suspend fun seed() {
        val userId = demoUserId()
        if (userId == null) {
            logger.warn { "seed [$domainName]: demo user not found — skipping (UserDomainSeeder not yet run?)" }
            return
        }

        val bookIds = availableBookIds()
        if (bookIds.isEmpty()) {
            logger.info { "seed [$domainName]: no books scanned yet — deferring to next restart" }
            return
        }

        val nowMs = clock.now().toEpochMilliseconds()

        // Six spans across the last 7 days, using up to 3 books in rotation.
        // Varied durations and speeds so the stats look like genuine usage.
        val spans =
            buildList {
                val book0 = bookIds[0]
                val book1 = if (bookIds.size > 1) bookIds[1] else book0
                val book2 = if (bookIds.size > 2) bookIds[2] else book1

                add(SpanSeed(daysAgo = 6, bookId = book0, durationMs = 30 * ONE_MINUTE_MS, speed = 1.0f))
                add(SpanSeed(daysAgo = 4, bookId = book0, durationMs = 45 * ONE_MINUTE_MS, speed = 1.25f))
                add(SpanSeed(daysAgo = 3, bookId = book1, durationMs = 20 * ONE_MINUTE_MS, speed = 1.0f))
                add(SpanSeed(daysAgo = 2, bookId = book1, durationMs = 60 * ONE_MINUTE_MS, speed = 1.5f))
                add(SpanSeed(daysAgo = 1, bookId = book0, durationMs = 25 * ONE_MINUTE_MS, speed = 1.5f))
                add(SpanSeed(daysAgo = 0, bookId = book2, durationMs = 15 * ONE_MINUTE_MS, speed = 1.0f))
            }

        for (span in spans) {
            val endedAt = nowMs - span.daysAgo * ONE_DAY_MS
            val startedAt = endedAt - span.durationMs
            val payload =
                ListeningEventSyncPayload(
                    id = Uuid.random().toString(),
                    bookId = span.bookId,
                    startPositionMs = 0L,
                    endPositionMs = span.durationMs,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    playbackSpeed = span.speed,
                    tz = "UTC",
                    deviceLabel = "Demo seeder",
                    revision = 0L,
                    updatedAt = 0L,
                    createdAt = 0L,
                    deletedAt = null,
                )
            when (val result = listeningEventRepository.upsert(value = payload, clientOpId = null, userId = userId)) {
                is AppResult.Success -> {
                    logger.info {
                        "seed [$domainName]: recorded ${span.durationMs / ONE_MINUTE_MS} min for book ${span.bookId} " +
                            "(${span.daysAgo} days ago)"
                    }
                }

                is AppResult.Failure -> {
                    logger.warn { "seed [$domainName]: failed for ${span.bookId} — ${result.error.code}" }
                }
            }
        }
    }

    /** Returns the demo user's id string, or null if not yet in the database. */
    private suspend fun demoUserId(): String? =
        suspendTransaction(db) {
            UserTable
                .selectAll()
                .where { UserTable.email eq UserDomainSeeder.DEMO_EMAIL }
                .firstOrNull()
                ?.get(UserTable.id)
                ?.value
        }

    /** Returns the ids of up to [DEMO_BOOK_COUNT] non-deleted books, ordered by sort title. */
    private suspend fun availableBookIds(): List<String> =
        suspendTransaction(db) {
            BookTable
                .selectAll()
                .where { BookTable.deletedAt.isNull() }
                .orderBy(BookTable.sortTitle)
                .limit(DEMO_BOOK_COUNT)
                .map { it[BookTable.id] }
        }
}

/** One seed span: a listening event [durationMs] ms long, [daysAgo] days in the past. */
private data class SpanSeed(
    val daysAgo: Long,
    val bookId: String,
    val durationMs: Long,
    val speed: Float,
)
