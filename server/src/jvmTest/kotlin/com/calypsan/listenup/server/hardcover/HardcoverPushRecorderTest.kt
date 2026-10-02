package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.activity.RealListen
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.services.StatsCascadeDeferred
import com.calypsan.listenup.server.services.StatsEvent
import com.calypsan.listenup.server.services.StatsRecorder
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant

private const val USER = "u1"
private const val BOOK = "book-1"
private const val T0 = 1_779_451_200_000L // 2026-05-22 12:00:00 UTC

/** The real StatsRecorder over a real database, with a real HardcoverPushRecorder behind its hook. */
private class RecorderRig(
    dbs: SqlTestDatabases,
    hookOverride: HardcoverPushHook? = null,
) {
    val sql: ListenUpDatabase = dbs.sql
    val driver = dbs.driver
    val clock = MutableClock(Instant.fromEpochMilliseconds(T0))
    val outbox = HardcoverOutbox(sql, clock)
    val links = HardcoverBookLinkStore(sql, clock)
    val connections = HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock)
    val preferences = HardcoverPreferences(sql, clock)
    val exclusions = HardcoverExclusions(sql)
    val nudged = CopyOnWriteArrayList<String>()
    private val events = ListeningEventRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
    val reads = BookReadsRepository(db = sql)
    lateinit var userStats: UserStatsRepository
    val recorder: StatsRecorder

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook(BOOK)
        val bus = ChangeBus()
        val registry = SyncRegistry()
        userStats = UserStatsRepository(db = sql, bus = bus, registry = registry)
        recorder =
            StatsRecorder(
                sql = sql,
                userStatsRepo = userStats,
                bookReadsRepository = reads,
                publicProfileMaintainer =
                    PublicProfileMaintainer(
                        sql = sql,
                        publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                        clock = clock,
                    ),
                activityRecorder = dbs.activityRecorder(bus = bus),
                statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStats),
                clock = clock,
                hardcoverPush =
                    hookOverride ?: HardcoverPushRecorder(
                        sql = sql,
                        connections = connections,
                        outbox = outbox,
                        links = links,
                        exclusions = exclusions,
                        nudge = HardcoverPushNudge { nudged += it },
                        clock = clock,
                    ),
            )
    }

    suspend fun connect() = connections.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("at", "rt", 604_800, HARDCOVER_SCOPES))

    suspend fun restart(
        atMs: Long,
        isReread: Boolean = false,
    ) = recorder.record(StatsEvent.BookRestarted(USER, BOOK, Instant.fromEpochMilliseconds(atMs), isReread))

    suspend fun listen(
        id: String,
        endedAtMs: Long,
        wallMs: Long,
        endPositionMs: Long,
    ) {
        val span =
            ListeningEventSyncPayload(
                id = id,
                bookId = BOOK,
                startPositionMs = endPositionMs - wallMs,
                endPositionMs = endPositionMs,
                startedAt = endedAtMs - wallMs,
                endedAt = endedAtMs,
                playbackSpeed = 1.0f,
                tz = "UTC",
                deviceLabel = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )
        events.upsert(span, clientOpId = null, userId = USER)
        recorder.record(StatsEvent.ListeningSessionClosed(userId = USER, span = span))
    }

    suspend fun finish(atMs: Long) = recorder.record(StatsEvent.BookCompleted(USER, BOOK, Instant.fromEpochMilliseconds(atMs)))

    suspend fun queued() = outbox.pendingFor(USER).map { it.payload }
}

private fun recorderTest(block: suspend RecorderRig.() -> Unit) = withSqlDatabase { runTest { RecorderRig(this@withSqlDatabase).block() } }

/** A hook whose every call throws, standing in for a broken outbox (a locked table, a bad row). */
private object ExplodingHook : HardcoverPushHook {
    override suspend fun onRealStart(
        userId: String,
        bookId: String,
        startedAt: Long,
        isReread: Boolean,
    ): Unit = error("outbox down")

    override suspend fun onSessionClosed(
        userId: String,
        bookId: String,
        positionMs: Long,
    ): Unit = error("outbox down")

    override suspend fun onReadAppended(
        userId: String,
        bookId: String,
        finishedAt: Long,
    ): Unit = error("outbox down")
}

/** StatsRecorder's three events reach the Hardcover outbox exactly when the spec says, and no other time. */
class HardcoverPushRecorderTest :
    FunSpec({

        test("a listen-through below the real-listen line queues nothing; crossing it queues START, then PROGRESS") {
            recorderTest {
                connect()
                restart(T0)
                listen("e1", endedAtMs = T0 + 30_000L, wallMs = 30_000L, endPositionMs = 30_000L)
                queued().shouldBeEmpty()

                listen("e2", endedAtMs = T0 + 90_000L, wallMs = 40_000L, endPositionMs = 70_000L)
                queued() shouldBe
                    listOf(
                        HardcoverPushPayload.Start(startedAt = T0, isReread = false),
                        HardcoverPushPayload.Progress(positionSeconds = 70L),
                    )
                nudged.toSet() shouldBe setOf(USER)
            }
        }

        test("later sessions coalesce into the one queued PROGRESS, holding the newest position") {
            recorderTest {
                connect()
                restart(T0)
                listen("e1", endedAtMs = T0 + RealListen.THRESHOLD_MS, wallMs = RealListen.THRESHOLD_MS, endPositionMs = 60_000L)
                listen("e2", endedAtMs = T0 + 600_000L, wallMs = 300_000L, endPositionMs = 360_000L)
                listen("e3", endedAtMs = T0 + 900_000L, wallMs = 300_000L, endPositionMs = 660_000L)
                queued().filterIsInstance<HardcoverPushPayload.Progress>() shouldBe listOf(HardcoverPushPayload.Progress(660L))
            }
        }

        test("the progress throttle: after a push, the next PROGRESS is not due for a sitting gap") {
            recorderTest {
                connect()
                restart(T0)
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(427_578L, null, HardcoverMatchMethod.ASIN))
                links.markProgressPushed(USER, BOOK, at = T0)
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                outbox
                    .pendingFor(USER)
                    .map { it.payload }
                    .filterIsInstance<HardcoverPushPayload.Progress>()
                    .size shouldBe 1
                outbox.nextWakeAt(USER) shouldBe T0 // START is due now…
                val start = outbox.head(USER)!!
                outbox.complete(start.id)
                outbox.nextWakeAt(USER) shouldBe T0 + RealListen.SITTING_GAP_MS // …PROGRESS waits the gap.
            }
        }

        test("the first finish queues FINISH and drops queued PROGRESS; a merged replay queues nothing more") {
            recorderTest {
                connect()
                restart(T0)
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                finish(T0 + 100_000L)
                queued() shouldBe listOf(HardcoverPushPayload.Start(T0, false), HardcoverPushPayload.Finish(T0 + 100_000L))

                // A known length makes the coverage rule merge a replay with no listening since.
                driver.execute(null, "UPDATE books SET total_duration = 36000000 WHERE id = '$BOOK'", 0)
                finish(T0 + 200_000L)
                queued().filterIsInstance<HardcoverPushPayload.Finish>().size shouldBe 1
            }
        }

        test("a session after the listen-through finished pushes no progress") {
            recorderTest {
                connect()
                restart(T0)
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                finish(T0 + 100_000L)
                listen("e2", endedAtMs = T0 + 200_000L, wallMs = 50_000L, endPositionMs = 140_000L)
                queued().filterIsInstance<HardcoverPushPayload.Progress>().shouldBeEmpty()
            }
        }

        test("a Hardcover read pulled after the listen-through began does not silence its progress") {
            recorderTest {
                connect()
                restart(T0)
                sql.bookReadsQueries.insert(
                    id = "pulled",
                    user_id = USER,
                    book_id = BOOK,
                    finished_at = T0 + 10_000L,
                    source = "hardcover",
                    created_at = T0 + 10_000L,
                )
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                queued().filterIsInstance<HardcoverPushPayload.Progress>() shouldBe listOf(HardcoverPushPayload.Progress(90L))
            }
        }

        test("a re-read is a new listen-through: its START is queued as a re-read and lifts an old suppression") {
            recorderTest {
                connect()
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(427_578L, null, HardcoverMatchMethod.ASIN))
                links.suppress(USER, BOOK, listenThrough = T0)
                val rereadAt = T0 + 86_400_000L
                restart(rereadAt, isReread = true)
                listen("e1", endedAtMs = rereadAt + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                queued().first() shouldBe HardcoverPushPayload.Start(rereadAt, isReread = true)
                links.linkFor(USER, BOOK)!!.suppressedListenThrough shouldBe null
            }
        }

        test("a suppressed listen-through queues nothing more") {
            recorderTest {
                connect()
                restart(T0)
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                links.recordAutomaticMatch(USER, BOOK, HardcoverMatch(427_578L, null, HardcoverMatchMethod.ASIN))
                links.suppress(USER, BOOK, listenThrough = T0)
                outbox.dropListenThrough(USER, BOOK, T0)

                listen("e2", endedAtMs = T0 + 400_000L, wallMs = 90_000L, endPositionMs = 180_000L)
                finish(T0 + 500_000L)
                queued().shouldBeEmpty()
            }
        }

        test("without a Hardcover connection nothing is queued") {
            recorderTest {
                restart(T0)
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                finish(T0 + 100_000L)
                queued().shouldBeEmpty()
                nudged.shouldBeEmpty()
            }
        }

        test("a book kept off Hardcover queues nothing: no START, no PROGRESS, no FINISH, and wakes no lane") {
            recorderTest {
                connect()
                sql.seedExclusion(USER, BOOK, at = T0)
                restart(T0)
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                listen("e2", endedAtMs = T0 + 600_000L, wallMs = 300_000L, endPositionMs = 390_000L)
                finish(T0 + 700_000L)

                queued().shouldBeEmpty()
                nudged.shouldBeEmpty()
            }
        }

        test("a hook that throws never fails the stats write that triggered it") {
            withSqlDatabase {
                runTest {
                    RecorderRig(this@withSqlDatabase, hookOverride = ExplodingHook).apply {
                        connect()
                        restart(T0)
                        listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                        finish(T0 + 100_000L)

                        reads.finishesForUserBook(USER, BOOK) shouldBe listOf(T0 + 100_000L)
                        userStats.getForUser(USER)!!.booksFinished shouldBe 1
                        userStats.getForUser(USER)!!.totalSecondsAllTime shouldBe 90L
                    }
                }
            }
        }

        test("an import (StatsCascadeDeferred) replays history and pushes none of it") {
            recorderTest {
                connect()
                withContext(StatsCascadeDeferred) {
                    restart(T0)
                    listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                    finish(T0 + 100_000L)
                }
                queued().shouldBeEmpty()
            }
        }

        test("Only when I finish: a real start and its sessions queue nothing") {
            recorderTest {
                connect()
                preferences.setShareMode(USER, HardcoverShareMode.FINISHED_ONLY)
                restart(T0)
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                listen("e2", endedAtMs = T0 + 400_000L, wallMs = 300_000L, endPositionMs = 390_000L)

                queued().shouldBeEmpty()
                nudged.shouldBeEmpty()
            }
        }

        test("Only when I finish: a finish queues FINISH on the listen-through, whose start dates the read") {
            recorderTest {
                connect()
                preferences.setShareMode(USER, HardcoverShareMode.FINISHED_ONLY)
                restart(T0)
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)
                finish(T0 + 100_000L)

                val row = outbox.pendingFor(USER).single()
                row.payload shouldBe HardcoverPushPayload.Finish(T0 + 100_000L)
                row.listenThrough shouldBe T0
                nudged.toSet() shouldBe setOf(USER)
            }
        }

        test("choosing As I listen again mid-book queues the next session's PROGRESS on the listen-through, and no START") {
            recorderTest {
                connect()
                preferences.setShareMode(USER, HardcoverShareMode.FINISHED_ONLY)
                restart(T0)
                listen("e1", endedAtMs = T0 + 90_000L, wallMs = 90_000L, endPositionMs = 90_000L)

                preferences.setShareMode(USER, HardcoverShareMode.AS_I_LISTEN)
                listen("e2", endedAtMs = T0 + 400_000L, wallMs = 300_000L, endPositionMs = 390_000L)

                val row = outbox.pendingFor(USER).single()
                row.payload shouldBe HardcoverPushPayload.Progress(positionSeconds = 390L)
                row.listenThrough shouldBe T0
            }
        }
    })
