@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakeProgressTracker
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

private const val SETTLE_ROUNDS = 50
private const val BOOK_ID = "book-test-1"
private const val USER_ID = "user-test-1"
private const val SPEED = 1.0f

/** A duration far past every pause in this file, so no pause here lands in the end credits. */
private const val LONG_BOOK_MS = 3_600_000L

/**
 * Tests for [PlaybackProgressReporter] — the single playback-session seam for iOS/Desktop.
 *
 * Verifies the listening-event recording fan-out against an in-memory
 * Room database: a play→pause session records exactly one row stamped with the account
 * user id and enqueues a sync op, book-finish finalizes the span, and — crucially — a
 * `null` recorder (the Android binding) records nothing, so Android never double-records.
 */
class PlaybackProgressReporterTest :
    FunSpec({

        test("play then pause records exactly one listening_events row for the account user and enqueues a sync op") {
            runTest {
                withReporterFixture(this, recorderEnabled = true) { reporter, db, enqueued, _, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    db.tentativeSpanDao().get().shouldNotBeNull()

                    setNow(61_000L)
                    reporter.onPlaybackPaused(BookId(BOOK_ID), positionMs = 60_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 1
                    events[0].userId shouldBe USER_ID
                    events[0].startPositionMs shouldBe 1_000L
                    events[0].endPositionMs shouldBe 60_000L
                    db.tentativeSpanDao().get().shouldBeNull()

                    enqueued.size shouldBe 1
                    enqueued[0].ownerUserId shouldBe USER_ID
                }
            }
        }

        test("a pause and the resume behind it keep their order even when the dispatcher reverses them") {
            runTest {
                val reversing = ReversingDispatcher(StandardTestDispatcher(testScheduler))
                withReporterFixture(
                    scope = this,
                    recorderEnabled = true,
                    reporterScope = CoroutineScope(reversing),
                ) { reporter, db, _, _, setNow ->
                    fun settle() =
                        repeat(SETTLE_ROUNDS) {
                            reversing.release()
                            testScheduler.advanceUntilIdle()
                        }

                    setNow(1_000L)
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 0L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    settle()

                    setNow(61_000L)
                    reporter.onPlaybackPaused(BookId(BOOK_ID), positionMs = 60_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 60_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    settle()

                    db.tentativeSpanDao().countRows() shouldBe 1
                    db
                        .tentativeSpanDao()
                        .get()
                        .shouldNotBeNull()
                        .startPositionMs shouldBe 60_000L
                    db
                        .listeningEventDao()
                        .getByBookForUser(USER_ID, BOOK_ID)
                        .single()
                        .endPositionMs shouldBe 60_000L
                }
            }
        }

        test("with no recorder (Android binding) play then pause records nothing — no double recording") {
            runTest {
                withReporterFixture(this, recorderEnabled = false) { reporter, db, enqueued, _, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    setNow(61_000L)
                    reporter.onPlaybackPaused(BookId(BOOK_ID), positionMs = 60_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).size shouldBe 0
                    db.tentativeSpanDao().get().shouldBeNull()
                    enqueued.size shouldBe 0
                }
            }
        }

        test("book finish finalizes the open span at the final position") {
            runTest {
                withReporterFixture(this, recorderEnabled = true) { reporter, db, _, _, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    setNow(120_000L)
                    reporter.onBookFinished(BookId(BOOK_ID), positionMs = 119_000L, durationMs = 119_000L)
                    advanceUntilIdle()

                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 1
                    events[0].endPositionMs shouldBe 119_000L
                    db.tentativeSpanDao().get().shouldBeNull()
                }
            }
        }

        test("periodic tick extends the open span without finalizing it") {
            runTest {
                withReporterFixture(this, recorderEnabled = true) { reporter, db, _, _, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    setNow(31_000L)
                    reporter.onPositionUpdate(BookId(BOOK_ID), positionMs = 30_000L, speed = SPEED)
                    advanceUntilIdle()

                    val span = db.tentativeSpanDao().get().shouldNotBeNull()
                    span.currentPositionMs shouldBe 30_000L
                    db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).size shouldBe 0
                }
            }
        }

        test("playback signals are forwarded to the ProgressTracker") {
            runTest {
                withReporterFixture(this, recorderEnabled = true) { reporter, _, _, tracker, _ ->
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    reporter.onPlaybackPaused(BookId(BOOK_ID), positionMs = 2_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    tracker.onPlaybackStartedCalls.size shouldBe 1
                    tracker.onPlaybackPausedCalls.size shouldBe 1
                }
            }
        }

        // A web player reports Playing again after every stall and at every file change of a multi-file book.
        // That is the same listen going on, not a new one: the span opened at its start must survive to the pause.
        test("Playing again with the span open (a stall or a file change ending) keeps one span from start to pause") {
            runTest {
                withReporterFixture(this, recorderEnabled = true) { reporter, db, enqueued, tracker, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(bookId = BookId(BOOK_ID), positionMs = 0L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    setNow(1_200_000L)
                    reporter.onPositionUpdate(bookId = BookId(BOOK_ID), positionMs = 1_199_000L, speed = SPEED)
                    advanceUntilIdle()

                    // Minute 45: the next file starts.
                    setNow(2_701_000L)
                    reporter.onPlaybackStarted(bookId = BookId(BOOK_ID), positionMs = 2_700_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    db.tentativeSpanDao().countRows() shouldBe 1

                    setNow(3_001_000L)
                    reporter.onPlaybackPaused(bookId = BookId(BOOK_ID), positionMs = 3_000_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    val event = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).single()
                    event.startPositionMs shouldBe 0L
                    event.endPositionMs shouldBe 3_000_000L
                    event.startedAt shouldBe 1_000L
                    event.endedAt shouldBe 3_001_000L
                    enqueued.size shouldBe 1
                    db.tentativeSpanDao().get().shouldBeNull()
                    // The session began once: a stall ending is not a fresh start of the book.
                    tracker.onPlaybackStartedCalls.size shouldBe 1
                }
            }
        }

        test("Playing after a pause opens a new span: only an open span absorbs a repeated Playing") {
            runTest {
                withReporterFixture(this, recorderEnabled = true) { reporter, db, _, tracker, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(bookId = BookId(BOOK_ID), positionMs = 0L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    setNow(61_000L)
                    reporter.onPlaybackPaused(bookId = BookId(BOOK_ID), positionMs = 60_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    setNow(121_000L)
                    reporter.onPlaybackStarted(bookId = BookId(BOOK_ID), positionMs = 60_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).size shouldBe 1
                    db
                        .tentativeSpanDao()
                        .get()
                        .shouldNotBeNull()
                        .startPositionMs shouldBe 60_000L
                    tracker.onPlaybackStartedCalls.size shouldBe 2
                }
            }
        }

        test("a seek splits the open span so the jumped-over range is not counted as listened") {
            runTest {
                withReporterFixture(this, recorderEnabled = true) { reporter, db, _, _, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(bookId = BookId(BOOK_ID), positionMs = 0L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    setNow(121_000L)
                    reporter.onSeek(bookId = BookId(BOOK_ID), beforeMs = 120_000L, afterMs = 3_000_000L, speed = SPEED)
                    advanceUntilIdle()
                    setNow(181_000L)
                    reporter.onPlaybackPaused(bookId = BookId(BOOK_ID), positionMs = 3_060_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    val events =
                        db
                            .listeningEventDao()
                            .getByBookForUser(USER_ID, BOOK_ID)
                            .map { it.startPositionMs to it.endPositionMs }
                    events.toSet() shouldBe setOf(0L to 120_000L, 3_000_000L to 3_060_000L)
                }
            }
        }

        test("playback stopping without a pause (an error, a teardown) closes the open span where it stopped") {
            runTest {
                withReporterFixture(this, recorderEnabled = true) { reporter, db, _, tracker, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(bookId = BookId(BOOK_ID), positionMs = 0L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    setNow(91_000L)
                    reporter.onPlaybackStopped(bookId = BookId(BOOK_ID), positionMs = 90_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    db
                        .listeningEventDao()
                        .getByBookForUser(USER_ID, BOOK_ID)
                        .single()
                        .endPositionMs shouldBe 90_000L
                    tracker.onPlaybackPausedCalls.size shouldBe 1

                    // A second stop has no span to close and saves nothing again.
                    reporter.onPlaybackStopped(bookId = BookId(BOOK_ID), positionMs = 90_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    tracker.onPlaybackPausedCalls.size shouldBe 1
                }
            }
        }

        test("a retry after playback stopped opens a fresh span rather than stretching the old one") {
            runTest {
                withReporterFixture(this, recorderEnabled = true) { reporter, db, _, _, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(bookId = BookId(BOOK_ID), positionMs = 0L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    setNow(91_000L)
                    reporter.onPlaybackStopped(bookId = BookId(BOOK_ID), positionMs = 90_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()
                    setNow(400_000L)
                    reporter.onPlaybackStarted(bookId = BookId(BOOK_ID), positionMs = 90_000L, speed = SPEED, durationMs = LONG_BOOK_MS)
                    advanceUntilIdle()

                    val span = db.tentativeSpanDao().get().shouldNotBeNull()
                    span.startPositionMs shouldBe 90_000L
                    span.startedAt shouldBe 400_000L
                }
            }
        }
    })

/**
 * Run [block] with a reporter wired to a real [ListeningEventRecorder] over an in-memory
 * database (so recording outcomes are asserted end-to-end) and a [FakeProgressTracker].
 * `setNow` controls the recorder's clock so spans have deterministic, non-zero duration.
 */
private suspend fun withReporterFixture(
    scope: TestScope,
    recorderEnabled: Boolean,
    reporterScope: CoroutineScope = scope,
    block: suspend (
        PlaybackProgressReporter,
        ListenUpDatabase,
        List<ReporterCapturedEnqueue>,
        FakeProgressTracker,
        setNow: (Long) -> Unit,
    ) -> Unit,
) {
    // Route Room IO through the test scheduler so advanceUntilIdle() drains the reporter's
    // fire-and-forget recorder writes deterministically (see TestDatabase KDoc).
    val db = createInMemoryTestDatabase(StandardTestDispatcher(scope.testScheduler))
    try {
        var now = 0L
        val captured = mutableListOf<ReporterCapturedEnqueue>()
        val realQueue =
            PendingOperationQueue(
                dao = db.pendingOperationV2Dao(),
                sender = PendingOperationSender { AppResult.Success(Unit) },
                nowMillis = { now },
            )

        val recorder =
            if (!recorderEnabled) {
                null
            } else {
                ListeningEventRecorder(
                    listeningEventDao = db.listeningEventDao(),
                    tentativeSpanDao = db.tentativeSpanDao(),
                    transactionRunner = RoomTransactionRunner(db),
                    enqueue = { entityId, payload, ownerUserId ->
                        captured.add(ReporterCapturedEnqueue(entityId, payload, ownerUserId))
                        realQueue.enqueue(OutboxChannels.ListeningEvents, entityId, OpKind.Upsert, payload, ownerUserId)
                    },
                    currentUserId = { USER_ID },
                    deviceInfo = DeviceInfoProvider { DeviceInfo(deviceName = "Test Device") },
                    clock =
                        object : Clock {
                            override fun now(): Instant = Instant.fromEpochMilliseconds(now)
                        },
                    timeZone = { TimeZone.currentSystemDefault() },
                )
            }

        val downloadRepository = mock<DownloadRepository> { everySuspend { deleteForBook(any()) } returns Unit }
        val tracker =
            FakeProgressTracker(
                downloadRepository = downloadRepository,
                positionRepository = FakePlaybackPositionRepository(),
                scope = scope,
            )

        val localPreferences: LocalPreferences = mock()
        every { localPreferences.autoRewindEnabled } returns MutableStateFlow(false)
        val reporter =
            PlaybackProgressReporter(
                tracker,
                recorder,
                reporterScope,
                localPreferences = localPreferences,
                nowMillis = { now },
            )

        block(reporter, db, captured, tracker) { now = it }
    } finally {
        db.close()
    }
}

/**
 * Runs what it is handed newest first, the worst a multi-threaded dispatcher (iOS's playback scope) can do: a pause
 * and the resume right behind it reach their coroutines the wrong way round.
 */
private class ReversingDispatcher(
    private val next: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private val held = ArrayDeque<Pair<CoroutineContext, Runnable>>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        held.addFirst(context to block)
    }

    fun release() {
        while (held.isNotEmpty()) {
            val (context, block) = held.removeFirst()
            next.dispatch(context, block)
        }
    }
}

/** Captured fields from a single recorder `enqueue` call. */
private data class ReporterCapturedEnqueue(
    val entityId: String,
    val payload: String,
    val ownerUserId: String,
)
