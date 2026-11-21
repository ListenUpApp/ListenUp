package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import com.calypsan.listenup.client.core.logging.RecordedLogEvent
import com.calypsan.listenup.client.core.logging.withRecordingLoggerFactory
import io.github.oshai.kotlinlogging.Level

private const val BOOK_ID = "book-test-1"
private const val USER_ID = "user-test-1"
private const val SPEED = 1.5f

/**
 * Two ways a listen used to go unrecorded while its positions kept saving, so nobody noticed:
 *
 * - **A play the recorder never heard.** Android resumes from the notification, lock screen, headset or car before
 *   the book it is playing is known; the play went by without opening a span, and nothing opened one later.
 * - **A span whose finalize keeps failing.** It stayed open as the recovery breadcrumb, and every later play bailed
 *   rather than open a new span over it, so one bad row silenced recording for good.
 */
class ListeningEventRecorderSelfHealTest :
    FunSpec({

        test("a listen whose play was never heard is recorded from the first tick that knows its book") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, enqueued ->
                    // No onPlay: the play arrived before the book was known.
                    now = 31_000L
                    recorder.onPeriodicTick(BOOK_ID, positionMs = 30_000L, playbackSpeed = SPEED)

                    val span = db.tentativeSpanDao().get().shouldNotBeNull()
                    span.startPositionMs shouldBe 30_000L
                    span.startedAt shouldBe 31_000L
                    span.playbackSpeed shouldBe SPEED

                    now = 61_000L
                    recorder.onPeriodicTick(BOOK_ID, positionMs = 75_000L, playbackSpeed = SPEED)
                    now = 91_000L
                    recorder.onPause(BOOK_ID, positionMs = 120_000L)

                    val event = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).single()
                    event.startPositionMs shouldBe 30_000L
                    event.endPositionMs shouldBe 120_000L
                    event.startedAt shouldBe 31_000L
                    event.endedAt shouldBe 91_000L
                    event.playbackSpeed shouldBe SPEED
                    enqueued shouldHaveSize 1
                    db.tentativeSpanDao().get().shouldBeNull()
                }
            }
        }

        test("opening a span late says so in the log") {
            runTest {
                var now = 1_000L
                withRecorderLog { recorderLines ->
                    withFixture(nowMillisProvider = { now }) { recorder, _, _ ->
                        now = 31_000L
                        recorder.onPeriodicTick(BOOK_ID, positionMs = 30_000L, playbackSpeed = SPEED)
                        now = 61_000L
                        recorder.onPeriodicTick(BOOK_ID, positionMs = 60_000L, playbackSpeed = SPEED)
                    }
                    val late = recorderLines().filter { it.message.orEmpty().contains("Opened span late") }
                    late shouldHaveSize 1
                    late.single().level shouldBe Level.INFO
                    late.single().message.orEmpty() shouldContain BOOK_ID
                }
            }
        }

        test("a span whose finalize keeps failing is set aside, so the next play is still recorded") {
            runTest {
                var now = 1_000L
                withStuckSpanRecorder(nowMillisProvider = { now }, processId = "process-stuck") { recorder, db, recorded ->
                    withRecorderLog { recorderLines ->
                        recorder.onPlay(BOOK_ID, positionMs = 0L, playbackSpeed = SPEED)
                        now = 31_000L
                        recorder.onPeriodicTick(BOOK_ID, positionMs = 30_000L, playbackSpeed = SPEED)
                        now = 61_000L
                        recorder.onPause(BOOK_ID, positionMs = 60_000L) // finalize fails: 1
                        now = 121_000L
                        recorder.onPlay(BOOK_ID, positionMs = 60_000L, playbackSpeed = SPEED) // fails: 2
                        now = 181_000L
                        recorder.onPlay(BOOK_ID, positionMs = 60_000L, playbackSpeed = SPEED) // fails: 3, set aside
                        now = 241_000L
                        recorder.onPause(BOOK_ID, positionMs = 120_000L)

                        val event = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).single()
                        event.startPositionMs shouldBe 60_000L
                        event.endPositionMs shouldBe 120_000L
                        event.startedAt shouldBe 181_000L
                        event.endedAt shouldBe 241_000L
                        recorded shouldHaveSize 1

                        // The stuck span is not dropped: it is still there for the next launch to recover.
                        val stuck = db.tentativeSpanDao().get().shouldNotBeNull()
                        db.tentativeSpanDao().countRows() shouldBe 1
                        stuck.startPositionMs shouldBe 0L
                        stuck.currentPositionMs shouldBe 30_000L

                        val errors = recorderLines().filter { it.level == Level.ERROR }
                        errors shouldHaveSize 1
                        errors.single().message.orEmpty() shouldContain stuck.id
                    }
                }
            }
        }

        test("a span set aside is recovered by the next launch") {
            runTest {
                var now = 1_000L
                val db = createInMemoryTestDatabase()
                try {
                    withStuckSpanRecorder(nowMillisProvider = { now }, processId = "process-stuck", db = db) { recorder, _, _ ->
                        recorder.onPlay(BOOK_ID, positionMs = 0L, playbackSpeed = SPEED)
                        now = 31_000L
                        recorder.onPeriodicTick(BOOK_ID, positionMs = 30_000L, playbackSpeed = SPEED)
                        repeat(MAX_FINALIZE_ATTEMPTS) {
                            now += 60_000L
                            recorder.onPlay(BOOK_ID, positionMs = 30_000L, playbackSpeed = SPEED)
                        }
                        now += 60_000L
                        recorder.onPause(BOOK_ID, positionMs = 90_000L)
                    }

                    now += 60_000L
                    workingRecorder(db, nowMillisProvider = { now }, processId = "process-next").recoverOrphan()

                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).sortedBy { it.startedAt }
                    events shouldHaveSize 2
                    events[0].startPositionMs shouldBe 0L
                    events[0].endPositionMs shouldBe 30_000L
                    events[0].endedAt shouldBe 31_000L
                    db.tentativeSpanDao().countRows() shouldBe 0
                } finally {
                    db.close()
                }
            }
        }

        test("a finalize that fails fewer times than the bound still holds the breadcrumb") {
            runTest {
                var now = 1_000L
                withStuckSpanRecorder(nowMillisProvider = { now }, processId = "process-stuck") { recorder, db, recorded ->
                    recorder.onPlay(BOOK_ID, positionMs = 0L, playbackSpeed = SPEED)
                    now = 31_000L
                    recorder.onPeriodicTick(BOOK_ID, positionMs = 30_000L, playbackSpeed = SPEED)
                    now = 61_000L
                    recorder.onPlay(BOOK_ID, positionMs = 30_000L, playbackSpeed = SPEED) // fails: 1

                    db.tentativeSpanDao().countRows() shouldBe 1
                    db
                        .tentativeSpanDao()
                        .get()
                        .shouldNotBeNull()
                        .startPositionMs shouldBe 0L
                    recorded.shouldBeEmpty()
                }
            }
        }
    })

/**
 * A recorder whose outbox refuses the first span it is handed, every time, and takes every later one: one bad row
 * among good ones.
 */
private suspend fun withStuckSpanRecorder(
    nowMillisProvider: () -> Long,
    processId: String,
    db: ListenUpDatabase? = null,
    block: suspend (ListeningEventRecorder, ListenUpDatabase, List<String>) -> Unit,
) {
    val database = db ?: createInMemoryTestDatabase()
    try {
        var stuckId: String? = null
        val recorded = mutableListOf<String>()
        val recorder =
            ListeningEventRecorder(
                listeningEventDao = database.listeningEventDao(),
                tentativeSpanDao = database.tentativeSpanDao(),
                transactionRunner = RoomTransactionRunner(database),
                enqueue = { entityId, _, _ ->
                    if (stuckId == null) stuckId = entityId
                    if (entityId == stuckId) error("Simulated outbox enqueue failure")
                    recorded += entityId
                },
                currentUserId = { USER_ID },
                deviceInfo = DeviceInfoProvider { DeviceInfo(deviceName = "Test Device") },
                processId = processId,
                clock = clockAt(nowMillisProvider),
                timeZone = { TimeZone.UTC },
            )
        block(recorder, database, recorded)
    } finally {
        if (db == null) database.close()
    }
}

private fun workingRecorder(
    db: ListenUpDatabase,
    nowMillisProvider: () -> Long,
    processId: String,
) = ListeningEventRecorder(
    listeningEventDao = db.listeningEventDao(),
    tentativeSpanDao = db.tentativeSpanDao(),
    transactionRunner = RoomTransactionRunner(db),
    enqueue = { _, _, _ -> },
    currentUserId = { USER_ID },
    deviceInfo = DeviceInfoProvider { DeviceInfo(deviceName = "Test Device") },
    processId = processId,
    clock = clockAt(nowMillisProvider),
    timeZone = { TimeZone.UTC },
)

private fun clockAt(nowMillisProvider: () -> Long): Clock =
    object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillisProvider())
    }

/** Runs [block] with what [ListeningEventRecorder] logs recorded, read through the function it is handed. */
private suspend fun withRecorderLog(block: suspend (recorderLines: () -> List<RecordedLogEvent>) -> Unit) =
    withRecordingLoggerFactory { recording ->
        block { recording.events.filter { it.loggerName.endsWith("ListeningEventRecorder") } }
    }
