package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.server.hardcover.HARDCOVER_SCOPES
import com.calypsan.listenup.server.hardcover.HardcoverBookLinkStore
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverExclusions
import com.calypsan.listenup.server.hardcover.HardcoverMe
import com.calypsan.listenup.server.hardcover.HardcoverOutbox
import com.calypsan.listenup.server.hardcover.HardcoverPushNudge
import com.calypsan.listenup.server.hardcover.HardcoverPushPayload
import com.calypsan.listenup.server.hardcover.HardcoverPushRecorder
import com.calypsan.listenup.server.hardcover.HardcoverTokenCipher
import com.calypsan.listenup.server.hardcover.HardcoverTokens
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

private const val USER = "u1"
private const val BOOK = "book-1"
private val EDMONTON = TimeZone.of("America/Edmonton")

/** When the reader pressed "Mark as finished": Oct 3, noon, in Edmonton. */
private val PRESSED_AT = LocalDateTime(2026, 10, 3, 12, 0).toInstant(EDMONTON).toEpochMilliseconds()

/** The day they said they finished: Sep 30, as the client sends it — local midnight in their zone. */
private val SEP_30 = LocalDate(2026, 9, 30).atStartOfDayIn(EDMONTON).toEpochMilliseconds()

private const val ONE_MINUTE_MS = 60_000L
private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000

/**
 * The real position → stats → Hardcover chain, with a connected Hardcover account, so one finish can be
 * read back from every place it is dated: `book_reads`, the FINISHED_BOOK activity and the FINISH push.
 */
private class FinishRig(
    dbs: SqlTestDatabases,
) {
    val sql = dbs.sql
    val driver = dbs.driver
    val clock = MutableClock(Instant.fromEpochMilliseconds(PRESSED_AT))
    private val outbox = HardcoverOutbox(sql, clock)
    private val connections =
        HardcoverConnectionStore(sql, HardcoverTokenCipher(HardcoverTokenCipher.deriveKey("secret")), clock)
    private val activities = ActivityRepository(db = sql)
    val positions: PlaybackPositionRepository

    init {
        sql.seedTestUser(USER)
        sql.seedTestLibraryAndFolder()
        sql.seedTestBook(BOOK)
        val bus = ChangeBus()
        val registry = SyncRegistry()
        val userStats = UserStatsRepository(db = sql, bus = bus, registry = registry)
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
                activityRecorder = dbs.activityRecorder(bus = bus),
                statsBackfill = UserStatsBackfillService(sql = sql, userStatsRepo = userStats, clock = clock),
                clock = clock,
                hardcoverPush =
                    HardcoverPushRecorder(
                        sql = sql,
                        connections = connections,
                        outbox = outbox,
                        links = HardcoverBookLinkStore(sql, clock),
                        exclusions = HardcoverExclusions(sql),
                        nudge = HardcoverPushNudge { },
                        clock = clock,
                    ),
            )
        positions =
            PlaybackPositionRepository(
                db = sql,
                bus = bus,
                registry = registry,
                clock = clock,
                statsRecorder = recorder,
            )
    }

    suspend fun connectHardcover() =
        connections.save(USER, HardcoverMe(42, "reader"), HardcoverTokens("at", "rt", 604_800, HARDCOVER_SCOPES))

    suspend fun record(
        finished: Boolean,
        lastPlayedAt: Long,
        finishedAt: Long? = null,
    ) = positions.recordPosition(
        userId = USER,
        bookId = BOOK,
        positionMs = if (finished) 99_000L else 0L,
        lastPlayedAt = lastPlayedAt,
        finished = finished,
        playbackSpeed = 1.0f,
        currentChapterId = null,
        finishedAt = finishedAt,
    )

    fun readsFinishedAt(): List<Long> = sql.bookReadsQueries.finishesForUserBook(USER, BOOK).executeAsList()

    suspend fun finishedActivitiesAt(): List<Long> =
        activities
            .page(before = null, limit = 50)
            .filter { it.type == ActivityType.FINISHED_BOOK }
            .map { it.occurredAt }

    suspend fun queuedFinishes(): List<Long> =
        outbox
            .pendingFor(USER)
            .map { it.payload }
            .filterIsInstance<HardcoverPushPayload.Finish>()
            .map { it.finishedAt }
}

private fun finishTest(block: suspend FinishRig.() -> Unit) = withSqlDatabase { runTest { FinishRig(this@withSqlDatabase).block() } }

/**
 * "Mark as finished" lets the reader say WHEN they finished. That day — not the moment they pressed
 * the button — is the finish: the read in `book_reads`, the FINISHED_BOOK activity, and the FINISH
 * Hardcover receives all carry it.
 */
class PlaybackPositionPickedFinishDateTest :
    FunSpec({

        test("a finish recorded with a picked day is dated that day everywhere it lands") {
            finishTest {
                connectHardcover()

                record(finished = true, lastPlayedAt = PRESSED_AT, finishedAt = SEP_30)

                readsFinishedAt() shouldBe listOf(SEP_30)
                finishedActivitiesAt() shouldBe listOf(SEP_30)
                queuedFinishes() shouldBe listOf(SEP_30)
                positions.getPosition(USER, BOOK).shouldNotBeNull().finishedAt shouldBe SEP_30
            }
        }

        test("a picked day beyond the clock-skew tolerance is clamped, in the read and on the position") {
            finishTest {
                val ceiling = PRESSED_AT + SKEW_TOLERANCE_MS

                record(finished = true, lastPlayedAt = PRESSED_AT, finishedAt = PRESSED_AT + THREE_DAYS_MS)

                readsFinishedAt() shouldBe listOf(ceiling)
                finishedActivitiesAt() shouldBe listOf(ceiling)
                positions.getPosition(USER, BOOK).shouldNotBeNull().finishedAt shouldBe ceiling
            }
        }

        test("a finish with no picked day is dated when it was played") {
            finishTest {
                record(finished = true, lastPlayedAt = PRESSED_AT - ONE_MINUTE_MS)

                readsFinishedAt() shouldBe listOf(PRESSED_AT - ONE_MINUTE_MS)
                finishedActivitiesAt() shouldBe listOf(PRESSED_AT - ONE_MINUTE_MS)
            }
        }

        test("a picked day that is not a real instant falls back to when it was played") {
            finishTest {
                record(finished = true, lastPlayedAt = PRESSED_AT, finishedAt = 0L)

                readsFinishedAt() shouldBe listOf(PRESSED_AT)
                finishedActivitiesAt() shouldBe listOf(PRESSED_AT)
            }
        }

        test("an imported finish carrying its own day is dated that day everywhere it lands") {
            finishTest {
                connectHardcover()

                positions.recordAllForImport(
                    listOf(
                        ImportPositionWrite(
                            userId = USER,
                            bookId = BOOK,
                            positionMs = 99_000L,
                            lastPlayedAt = PRESSED_AT,
                            finished = true,
                            playbackSpeed = 1.0f,
                            currentChapterId = null,
                            finishedAt = SEP_30,
                        ),
                    ),
                )

                readsFinishedAt() shouldBe listOf(SEP_30)
                finishedActivitiesAt() shouldBe listOf(SEP_30)
                queuedFinishes() shouldBe listOf(SEP_30)
                positions.getPosition(USER, BOOK).shouldNotBeNull().finishedAt shouldBe SEP_30
            }
        }

        test("a stats rebuild that recovers a lost finish dates it by the position's finish day") {
            finishTest {
                // A finished position whose completion cascade never ran (a crash between the two).
                sql.playbackPositionsQueries.insert(
                    id = "pos-1",
                    user_id = USER,
                    book_id = BOOK,
                    position_ms = 99_000L,
                    last_played_at = PRESSED_AT,
                    finished = 1L,
                    playback_speed = 1.0,
                    volume_boost_db = 0.0,
                    measured_gain_db = null,
                    finished_at = SEP_30,
                    has_custom_speed = 0L,
                    has_custom_boost = 0L,
                    current_chapter_id = null,
                    revision = 1L,
                    created_at = PRESSED_AT,
                    updated_at = PRESSED_AT,
                    deleted_at = null,
                    client_op_id = null,
                )

                reconcileBookReadsFromPositions(sql, USER, nowMs = PRESSED_AT)

                readsFinishedAt() shouldBe listOf(SEP_30)
            }
        }

        test("re-dating a finish with nothing listened since moves the same read back to the picked day") {
            finishTest {
                // A known length lets the coverage rule see that nothing was listened between the finishes.
                driver.execute(null, "UPDATE books SET total_duration = 36000000 WHERE id = '$BOOK'", 0)

                // Finished on the day it was pressed, then un-finished to correct the date…
                record(finished = true, lastPlayedAt = PRESSED_AT, finishedAt = PRESSED_AT)
                clock.instant = Instant.fromEpochMilliseconds(PRESSED_AT + ONE_MINUTE_MS)
                record(finished = false, lastPlayedAt = PRESSED_AT + ONE_MINUTE_MS)

                // …and finished again, this time saying Sep 30.
                clock.instant = Instant.fromEpochMilliseconds(PRESSED_AT + 2 * ONE_MINUTE_MS)
                record(finished = true, lastPlayedAt = PRESSED_AT + 2 * ONE_MINUTE_MS, finishedAt = SEP_30)

                readsFinishedAt() shouldBe listOf(SEP_30)
            }
        }
    })
