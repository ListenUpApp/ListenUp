@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.playback

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
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakePlaybackPositionRepository
import com.calypsan.listenup.client.test.fake.FakeProgressTracker
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

private const val BOOK_ID = "book-test-1"
private const val USER_ID = "user-test-1"
private const val SPEED = 1.0f

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
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED)
                    advanceUntilIdle()
                    db.tentativeSpanDao().get().shouldNotBeNull()

                    setNow(61_000L)
                    reporter.onPlaybackPaused(BookId(BOOK_ID), positionMs = 60_000L, speed = SPEED)
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

        test("with no recorder (Android binding) play then pause records nothing — no double recording") {
            runTest {
                withReporterFixture(this, recorderEnabled = false) { reporter, db, enqueued, _, setNow ->
                    setNow(1_000L)
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED)
                    setNow(61_000L)
                    reporter.onPlaybackPaused(BookId(BOOK_ID), positionMs = 60_000L, speed = SPEED)
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
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED)
                    advanceUntilIdle()

                    setNow(120_000L)
                    reporter.onBookFinished(BookId(BOOK_ID), finalPositionMs = 119_000L)
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
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED)
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
                    reporter.onPlaybackStarted(BookId(BOOK_ID), positionMs = 1_000L, speed = SPEED)
                    reporter.onPlaybackPaused(BookId(BOOK_ID), positionMs = 2_000L, speed = SPEED)
                    advanceUntilIdle()

                    tracker.onPlaybackStartedCalls.size shouldBe 1
                    tracker.onPlaybackPausedCalls.size shouldBe 1
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

        val reporter = PlaybackProgressReporter(tracker, recorder, scope)

        block(reporter, db, captured, tracker) { now = it }
    } finally {
        db.close()
    }
}

/** Captured fields from a single recorder `enqueue` call. */
private data class ReporterCapturedEnqueue(
    val entityId: String,
    val payload: String,
    val ownerUserId: String,
)
