package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.SittingStart
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.ControlFrame
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

private const val MINUTE = 60_000L
private const val NOW = 1_800_000_000_000L

/**
 * The sittings are rebuilt whenever what they are built from changes: a listen arriving, a start or finish
 * announced, an import's history at once (and the boot backfill over everything). Old clients' per-span
 * activity rows keep being written beside them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsRecorderSittingsTest :
    FunSpec({

        test("a listen arriving builds its sitting, and its per-span activity row is still written") {
            withRecorder {
                events.upsert(span("e1", from = 0, to = 10), clientOpId = null, userId = USER)

                sittings.liveFor(USER, BOOK).single().listenedMs shouldBe 10 * MINUTE
                sql.activitiesQueries.countAll().executeAsOne() shouldBe 1L
            }
        }

        test("a second listen in the same sitting grows the one row") {
            withRecorder {
                events.upsert(span("e1", from = 0, to = 10), clientOpId = null, userId = USER)
                events.upsert(span("e2", from = 30, to = 50), clientOpId = null, userId = USER)

                sittings.liveFor(USER, BOOK).single().listenedMs shouldBe 30 * MINUTE
            }
        }

        test("the listen that makes a start real carries the start in its sitting") {
            withRecorder {
                recorder.record(
                    StatsEvent.BookRestarted(USER, BOOK, Instant.fromEpochMilliseconds(minute(0)), isReread = false),
                )
                events.upsert(span("e1", from = 0, to = 10), clientOpId = null, userId = USER)

                sittings.liveFor(USER, BOOK).single().beganRead shouldBe SittingStart.FIRST
            }
        }

        test("a finish recorded after the last listen marks the sitting finished") {
            withRecorder {
                events.upsert(span("e1", from = 0, to = 10), clientOpId = null, userId = USER)
                recorder.record(StatsEvent.BookCompleted(USER, BOOK, Instant.fromEpochMilliseconds(minute(11))))

                sittings.liveFor(USER, BOOK).single().finishedRead shouldBe true
            }
        }

        test("an import builds no sitting per row; its bulk recompute builds them all at once") {
            withRecorder {
                withContext(StatsCascadeDeferred) {
                    events.upsert(span("e1", from = 0, to = 10), clientOpId = null, userId = USER)
                    events.upsert(span("e2", from = 300, to = 320), clientOpId = null, userId = USER)
                }
                sittings.liveFor(USER, BOOK).shouldBeEmpty()

                recorder.record(StatsEvent.BulkRecompute(USER))

                sittings.liveFor(USER, BOOK) shouldHaveSize 2
            }
        }

        test("the backfill builds every user's history, a page at a time, and a second run writes nothing") {
            withRecorder { scope ->
                for (user in listOf("ann", "bea", "cy")) {
                    sql.seedTestUser(user)
                    withContext(StatsCascadeDeferred) {
                        events.upsert(span("$user-1", from = 0, to = 10), clientOpId = null, userId = user)
                    }
                }
                val frames = mutableListOf<ControlFrame>()
                scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                    bus.subscribeControl().toList(frames)
                }
                val backfill = SittingsBackfill(db = sql, reconciler = reconciler, pageSize = 1)

                backfill.run() shouldBe 3
                backfill.run() shouldBe 0

                listOf("ann", "bea", "cy").forEach { sittings.liveFor(it, BOOK) shouldHaveSize 1 }
                // One nudge per user whose sittings were written, none for the run that wrote nothing.
                frames.map { it.control } shouldBe List(3) { SyncControl.LibraryDataChanged }
            }
        }
    })

private const val USER = "kyle"
private const val BOOK = "red-rising"

private fun minute(m: Long) = NOW - 1_000 * MINUTE + m * MINUTE

private fun span(
    id: String,
    from: Long,
    to: Long,
) = ListeningEventSyncPayload(
    id = id,
    bookId = BOOK,
    startPositionMs = 0L,
    endPositionMs = (to - from) * MINUTE,
    startedAt = minute(from),
    endedAt = minute(to),
    playbackSpeed = 1.0f,
    tz = "UTC",
    deviceLabel = null,
    revision = 0L,
    updatedAt = 0L,
    createdAt = 0L,
    deletedAt = null,
)

/** A [StatsRecorder] wired to a real [SittingReconciler], and the listening-event repository that feeds it. */
private class RecorderFixture(
    databases: SqlTestDatabases,
) {
    val sql = databases.sql
    val bus = ChangeBus()
    private val clock = FixedClock(Instant.fromEpochMilliseconds(NOW))
    private val registry = SyncRegistry()
    private val userStats = UserStatsRepository(db = sql, bus = bus, registry = registry)
    val sittings = SittingRepository(db = sql, bus = bus, registry = registry, driver = databases.driver)
    val reconciler = SittingReconciler(db = sql, sittings = sittings, bus = bus, clock = clock)
    val recorder =
        StatsRecorder(
            sql = sql,
            userStatsRepo = userStats,
            bookReadsRepository = BookReadsRepository(db = sql, clock = clock),
            publicProfileMaintainer =
                PublicProfileMaintainer(
                    sql = sql,
                    publicProfileRepo = PublicProfileRepository(db = sql, bus = bus, registry = registry),
                    clock = clock,
                ),
            activityRecorder = databases.activityRecorder(bus = bus),
            statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStats, clock = clock),
            clock = clock,
            sittings = reconciler,
        )
    val events = ListeningEventRepository(db = sql, bus = bus, registry = registry, statsRecorder = recorder)
}

private fun withRecorder(block: suspend RecorderFixture.(TestScope) -> Unit) =
    withSqlDatabase {
        sql.seedTestLibraryAndFolder()
        sql.seedTestUser(USER)
        sql.seedTestBook(BOOK)
        val fixture = RecorderFixture(this)
        runTest { fixture.block(this) }
    }
