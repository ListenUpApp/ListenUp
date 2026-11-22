package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

private const val BOOK_ID = "book-test-1"
private const val USER_ID = "user-test-1"
private const val DEVICE_LABEL = "Test Device"
private const val SPEED = 1.0f
private const val START_POSITION = 1000L

/**
 * POSITION-02 — [ListeningEventRecorder]'s same-process finalize-then-reopen flow
 * ([ListeningEventRecorder.onSeek]) must preserve the [finalizeCurrentSpan]
 * breadcrumb the same way [ListeningEventRecorder.onPlay]'s cross-process branch already does:
 * when the finalize's enqueue fails, the tentative row survives as the ONLY thing
 * [ListeningEventRecorder.recoverOrphan] can re-promote from — opening a new span on top of it
 * would silently destroy that breadcrumb and lose the whole original span, not just the failed
 * write. Split out of [ListeningEventRecorderTest] (which is already near detekt's `LargeClass`
 * ceiling) the same way that file's own fixture helpers are shared across it.
 */
class ListeningEventRecorderFinalizeBreadcrumbTest :
    FunSpec({

        test("onSeek preserves the breadcrumb when finalize's enqueue fails — no new span opened") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }, failEnqueue = true) { recorder, db, _ ->
                    recorder.onPlay(BOOK_ID, START_POSITION, SPEED)

                    now = 31_000L
                    recorder.onSeek(positionBeforeSeek = 30_000L, positionAfterSeek = 90_000L)

                    // The ORIGINAL span survives untouched — not replaced by a fresh one at the
                    // post-seek position. countRows proves no second row was opened on top of it.
                    db.tentativeSpanDao().countRows() shouldBe 1
                    val span = db.tentativeSpanDao().get().shouldNotBeNull()
                    span.startPositionMs shouldBe START_POSITION
                    span.currentPositionMs shouldBe START_POSITION
                    span.startedAt shouldBe 1_000L

                    db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).size shouldBe 0
                }
            }
        }

        test("recoverOrphan re-promotes a breadcrumb a failed onSeek left behind, on a fresh process") {
            runTest {
                var now = 1_000L
                val db = createInMemoryTestDatabase()
                try {
                    val failingQueue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = PendingOperationSender { AppResult.Success(Unit) },
                            nowMillis = { now },
                        )
                    val crashedRecorder =
                        ListeningEventRecorder(
                            listeningEventDao = db.listeningEventDao(),
                            tentativeSpanDao = db.tentativeSpanDao(),
                            transactionRunner = RoomTransactionRunner(db),
                            enqueue = { _, _, _ -> error("Simulated outbox enqueue failure") },
                            currentUserId = { USER_ID },
                            deviceInfo = DeviceInfoProvider { DeviceInfo(deviceName = DEVICE_LABEL) },
                            processId = "process-crashed",
                            clock =
                                object : Clock {
                                    override fun now(): Instant = Instant.fromEpochMilliseconds(now)
                                },
                            timeZone = { TimeZone.currentSystemDefault() },
                        )

                    crashedRecorder.onPlay(BOOK_ID, START_POSITION, SPEED)
                    now = 20_000L
                    crashedRecorder.onPeriodicTick(BOOK_ID, 15_000L, playbackSpeed = SPEED)
                    now = 31_000L
                    // The seek's finalize fails — the span as of the LAST HEARTBEAT (not the
                    // seek's own position) survives as the breadcrumb, exactly as the tests above
                    // pin: onSeek's own finalize attempt never touches the stored row on failure.
                    crashedRecorder.onSeek(positionBeforeSeek = 30_000L, positionAfterSeek = 90_000L)
                    db.tentativeSpanDao().get().shouldNotBeNull()

                    // App restarts: a new process, a working outbox.
                    val captured = mutableListOf<CapturedEnqueue>()
                    now = 40_000L
                    val freshRecorder =
                        ListeningEventRecorder(
                            listeningEventDao = db.listeningEventDao(),
                            tentativeSpanDao = db.tentativeSpanDao(),
                            transactionRunner = RoomTransactionRunner(db),
                            enqueue = { entityId, payload, ownerUserId ->
                                captured.add(CapturedEnqueue(entityId, payload, ownerUserId))
                                failingQueue.enqueue(OutboxChannels.ListeningEvents, entityId, OpKind.Upsert, payload, ownerUserId)
                            },
                            currentUserId = { USER_ID },
                            deviceInfo = DeviceInfoProvider { DeviceInfo(deviceName = DEVICE_LABEL) },
                            processId = "process-fresh",
                            clock =
                                object : Clock {
                                    override fun now(): Instant = Instant.fromEpochMilliseconds(now)
                                },
                            timeZone = { TimeZone.currentSystemDefault() },
                        )

                    freshRecorder.recoverOrphan()

                    // The breadcrumb is gone, and the ORIGINAL (pre-seek) span was finalized —
                    // the failed seek never lost the listen, it was just deferred to this recovery.
                    db.tentativeSpanDao().get().shouldBeNull()
                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 1
                    events[0].startPositionMs shouldBe START_POSITION
                    events[0].startedAt shouldBe 1_000L
                    events[0].endPositionMs shouldBe 15_000L
                    events[0].endedAt shouldBe 20_000L
                    captured.size shouldBe 1
                } finally {
                    db.close()
                }
            }
        }
    })
