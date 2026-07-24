@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TentativeSpanEntity
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
 * Unit tests for [ListeningEventRecorder] — the per-span recording state machine.
 *
 * Uses an in-memory Room database and a captured pending-op list to verify all 8
 * specified cases without exercising the sync or server layers.
 */
class ListeningEventRecorderTest :
    FunSpec({

        // ── Case 1: onPlay from idle ──────────────────────────────────────────────

        test("onPlay from idle creates tentative_span with correct fields") {
            runTest {
                withFixture(nowMillis = 1_000L) { recorder, db, _ ->
                    recorder.onPlay(BOOK_ID, START_POSITION, SPEED)

                    val span = db.tentativeSpanDao().get()
                    span.shouldNotBeNull()
                    span.bookId shouldBe BOOK_ID
                    span.userId shouldBe USER_ID
                    span.startPositionMs shouldBe START_POSITION
                    span.currentPositionMs shouldBe START_POSITION
                    span.startedAt shouldBe 1_000L
                    span.lastHeartbeatAt shouldBe 1_000L
                    span.playbackSpeed shouldBe SPEED
                    span.tz shouldBe TimeZone.currentSystemDefault().id
                    span.deviceLabel shouldBe DEVICE_LABEL
                }
            }
        }

        // ── Case 2: onPeriodicTick while playing ──────────────────────────────────

        test("onPeriodicTick advances currentPositionMs and lastHeartbeatAt; other fields unchanged") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, _ ->
                    recorder.onPlay(BOOK_ID, START_POSITION, SPEED)

                    now = 31_000L
                    recorder.onPeriodicTick(5_000L)

                    val span = db.tentativeSpanDao().get()
                    span.shouldNotBeNull()
                    span.currentPositionMs shouldBe 5_000L
                    span.lastHeartbeatAt shouldBe 31_000L
                    // Other fields unchanged
                    span.startPositionMs shouldBe START_POSITION
                    span.startedAt shouldBe 1_000L
                    span.playbackSpeed shouldBe SPEED
                }
            }
        }

        // ── Case 3: onPause while playing ────────────────────────────────────────

        test("onPause finalizes span: listening_event written, tentative deleted, pending op enqueued") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, enqueuedOps ->
                    recorder.onPlay(BOOK_ID, START_POSITION, SPEED)

                    now = 61_000L
                    recorder.onPause(positionMs = 60_000L)

                    // Tentative span must be gone
                    db.tentativeSpanDao().get().shouldBeNull()

                    // Listening event row must exist
                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 1
                    val event = events[0]
                    event.bookId shouldBe BOOK_ID
                    event.userId shouldBe USER_ID
                    event.startPositionMs shouldBe START_POSITION
                    event.endPositionMs shouldBe 60_000L
                    event.startedAt shouldBe 1_000L
                    event.endedAt shouldBe 61_000L
                    event.playbackSpeed shouldBe SPEED
                    event.tz shouldBe TimeZone.currentSystemDefault().id
                    event.deviceLabel shouldBe DEVICE_LABEL

                    // Pending op enqueued with correct fields
                    enqueuedOps.size shouldBe 1
                    val op = enqueuedOps[0]
                    op.entityId shouldBe event.id
                    op.ownerUserId shouldBe USER_ID

                    // Payload round-trips correctly
                    val req = contractJson.decodeFromString(RecordListeningEventRequest.serializer(), op.payload)
                    req.bookId shouldBe BOOK_ID
                    req.startPositionMs shouldBe START_POSITION
                    req.endPositionMs shouldBe 60_000L
                    req.startedAt shouldBe 1_000L
                    req.endedAt shouldBe 61_000L
                    req.playbackSpeed shouldBe SPEED
                }
            }
        }

        // ── Case 4: onSpeedChange while playing ──────────────────────────────────

        test("onSpeedChange finalizes span at OLD speed; new span opens at NEW speed") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, enqueuedOps ->
                    recorder.onPlay(BOOK_ID, START_POSITION, oldSpeed = 1.0f)

                    now = 31_000L
                    recorder.onSpeedChange(positionMs = 30_000L, newSpeed = 1.5f)

                    // Old span finalized with old speed
                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 1
                    events[0].playbackSpeed shouldBe 1.0f
                    events[0].endPositionMs shouldBe 30_000L

                    // Wire payload carries the OLD speed
                    enqueuedOps.size shouldBe 1
                    val req =
                        contractJson.decodeFromString(
                            RecordListeningEventRequest.serializer(),
                            enqueuedOps[0].payload,
                        )
                    req.playbackSpeed shouldBe 1.0f

                    // New tentative span open with new speed at the speed-change position
                    val span = db.tentativeSpanDao().get()
                    span.shouldNotBeNull()
                    span.playbackSpeed shouldBe 1.5f
                    span.startPositionMs shouldBe 30_000L
                }
            }
        }

        // ── Case 5: onSeek while playing ─────────────────────────────────────────

        test("onSeek finalizes span at pre-seek position; new span starts at post-seek position") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, enqueuedOps ->
                    recorder.onPlay(BOOK_ID, START_POSITION, SPEED)

                    now = 31_000L
                    recorder.onSeek(positionBeforeSeek = 30_000L, positionAfterSeek = 90_000L)

                    // Old span finalized at pre-seek position
                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 1
                    events[0].endPositionMs shouldBe 30_000L

                    // Pending op enqueued
                    enqueuedOps.size shouldBe 1

                    // New tentative span at post-seek position
                    val span = db.tentativeSpanDao().get()
                    span.shouldNotBeNull()
                    span.startPositionMs shouldBe 90_000L
                    span.currentPositionMs shouldBe 90_000L
                }
            }
        }

        // ── Case 6: onMediaItemTransition while playing ───────────────────────────

        test("onMediaItemTransition finalizes span for old book; new span opens for new book") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, enqueuedOps ->
                    recorder.onPlay(BOOK_ID, START_POSITION, SPEED)
                    now = 61_000L
                    recorder.onPeriodicTick(60_000L)

                    recorder.onMediaItemTransition(newBookId = "book-test-2", newStartPositionMs = 0L)

                    // Old span finalized for old book
                    val oldEvents = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    oldEvents.size shouldBe 1
                    oldEvents[0].bookId shouldBe BOOK_ID

                    // Pending op enqueued for old book span
                    enqueuedOps.size shouldBe 1

                    // New tentative span for new book
                    val span = db.tentativeSpanDao().get()
                    span.shouldNotBeNull()
                    span.bookId shouldBe "book-test-2"
                    span.startPositionMs shouldBe 0L
                }
            }
        }

        // ── Case 7: Zero-duration spans dropped ──────────────────────────────────

        test("play then pause at same millisecond produces no listening_event and no pending op") {
            runTest {
                // Fixed clock: both onPlay and onPause see the same millisecond → zero duration
                withFixture(nowMillis = 5_000L) { recorder, db, enqueuedOps ->
                    recorder.onPlay(BOOK_ID, START_POSITION, SPEED)
                    recorder.onPause(positionMs = 30_000L)

                    // No listening event
                    db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).size shouldBe 0

                    // No pending op
                    enqueuedOps.size shouldBe 0

                    // Tentative span cleaned up
                    db.tentativeSpanDao().get().shouldBeNull()
                }
            }
        }

        // ── Case 8: recoverOrphan ─────────────────────────────────────────────────

        test("recoverOrphan promotes orphan tentative_span to listening_event using lastHeartbeatAt as endedAt") {
            runTest {
                withFixture(nowMillis = 99_000L) { recorder, db, enqueuedOps ->
                    // Seed an orphan span — simulates app crash during playback
                    val orphanId = "orphan-span-id-1"
                    db.tentativeSpanDao().upsertSingleton(
                        TentativeSpanEntity(
                            id = orphanId,
                            userId = USER_ID,
                            bookId = BOOK_ID,
                            startPositionMs = 10_000L,
                            currentPositionMs = 45_000L,
                            startedAt = 50_000L,
                            lastHeartbeatAt = 80_000L,
                            playbackSpeed = 1.25f,
                            tz = "America/New_York",
                            deviceLabel = "Crashed Device",
                        ),
                    )

                    recorder.recoverOrphan()

                    // Tentative span deleted
                    db.tentativeSpanDao().get().shouldBeNull()

                    // Listening event created with currentPositionMs as end, lastHeartbeatAt as endedAt
                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 1
                    val event = events[0]
                    event.id shouldBe orphanId
                    event.startPositionMs shouldBe 10_000L
                    event.endPositionMs shouldBe 45_000L // tentative.currentPositionMs
                    event.startedAt shouldBe 50_000L
                    event.endedAt shouldBe 80_000L // tentative.lastHeartbeatAt
                    event.playbackSpeed shouldBe 1.25f
                    event.tz shouldBe "America/New_York"
                    event.deviceLabel shouldBe "Crashed Device"

                    // Pending op enqueued
                    enqueuedOps.size shouldBe 1
                    val op = enqueuedOps[0]
                    op.entityId shouldBe orphanId
                }
            }
        }

        // ── Orphan recovery must not resurrect an already-committed event ─────────

        test("recoverOrphan does not clobber an already-synced/tombstoned event row") {
            runTest {
                withFixture(nowMillis = 99_000L) { recorder, db, _ ->
                    val eventId = "evt-already-synced"

                    // The event was finalized on a prior run, synced (revision advanced),
                    // and later tombstoned server-side — exactly the state a re-promoted
                    // orphan must NOT regress back to revision=0 / deletedAt=null.
                    db.listeningEventDao().upsert(
                        com.calypsan.listenup.client.data.local.db.ListeningEventEntity(
                            id = eventId,
                            userId = USER_ID,
                            bookId = BOOK_ID,
                            startPositionMs = 10_000L,
                            endPositionMs = 45_000L,
                            startedAt = 50_000L,
                            endedAt = 80_000L,
                            playbackSpeed = 1.25f,
                            tz = "America/New_York",
                            deviceLabel = "Crashed Device",
                            revision = 7L,
                            deletedAt = 90_000L,
                        ),
                    )

                    // An orphan tentative span survives for the SAME id (delete failed
                    // after the event upsert on the prior run).
                    db.tentativeSpanDao().upsertSingleton(
                        TentativeSpanEntity(
                            id = eventId,
                            userId = USER_ID,
                            bookId = BOOK_ID,
                            startPositionMs = 10_000L,
                            currentPositionMs = 45_000L,
                            startedAt = 50_000L,
                            lastHeartbeatAt = 80_000L,
                            playbackSpeed = 1.25f,
                            tz = "America/New_York",
                            deviceLabel = "Crashed Device",
                        ),
                    )

                    recorder.recoverOrphan()

                    // The committed row is untouched — not regressed to revision=0/deletedAt=null.
                    val row = db.listeningEventDao().getById(eventId).shouldNotBeNull()
                    row.revision shouldBe 7L
                    row.deletedAt shouldBe 90_000L
                    // And the orphan span is cleaned up so it can't loop forever.
                    db.tentativeSpanDao().get().shouldBeNull()
                }
            }
        }

        // ── Failed enqueue must roll back atomically — span survives for recovery ──

        test("failed enqueue rolls back the finalize: tentative_span survives so recoverOrphan can re-promote") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }, failEnqueue = true) { recorder, db, _ ->
                    recorder.onPlay(BOOK_ID, START_POSITION, SPEED)

                    now = 61_000L
                    // The outbox enqueue fails mid-finalize. The finalize must be atomic:
                    // the tentative-span delete (and event upsert) must roll back so the
                    // ONLY breadcrumb recoverOrphan reads survives. Before the fix the span
                    // was deleted unconditionally and the event stranded at revision=0.
                    recorder.onPause(positionMs = 60_000L)

                    // The tentative span MUST still exist — the delete rolled back.
                    db.tentativeSpanDao().get().shouldNotBeNull()

                    // The event upsert rolled back too — no half-committed listening_event.
                    db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).size shouldBe 0
                }
            }
        }

        // ── F6: process identity — onPlay recovers a prior-process orphan, never overwrites it ──

        test("onPlay recovers a prior-process orphan instead of overwriting it; new live span is separate") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }, processId = "process-current") { recorder, db, enqueuedOps ->
                    // Seed an orphan from a PRIOR process launch — yesterday's OS-killed ~2 h listen.
                    db.tentativeSpanDao().upsertSingleton(
                        TentativeSpanEntity(
                            id = "orphan-prior",
                            userId = USER_ID,
                            bookId = BOOK_ID,
                            startPositionMs = 100_000L,
                            currentPositionMs = 7_300_000L,
                            startedAt = 500_000L,
                            lastHeartbeatAt = 7_700_000L,
                            playbackSpeed = 1.0f,
                            tz = "America/New_York",
                            deviceLabel = "Yesterday Device",
                            processId = "process-prior",
                        ),
                    )

                    // Today: cold start, user immediately hits play while catch-up is still paging.
                    now = 8_000_000L
                    recorder.onPlay(BOOK_ID, positionMs = 50_000L, SPEED)

                    // The prior session's ~2 h span was RECOVERED (finalized), not lost/overwritten.
                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 1
                    events[0].id shouldBe "orphan-prior"
                    events[0].startPositionMs shouldBe 100_000L
                    events[0].endPositionMs shouldBe 7_300_000L // orphan.currentPositionMs
                    events[0].endedAt shouldBe 7_700_000L // orphan.lastHeartbeatAt
                    enqueuedOps.size shouldBe 1
                    enqueuedOps[0].entityId shouldBe "orphan-prior"

                    // A fresh live span for today's session exists, stamped with the CURRENT process.
                    val span = db.tentativeSpanDao().get()
                    span.shouldNotBeNull()
                    span.id shouldNotBe "orphan-prior"
                    span.processId shouldBe "process-current"
                    span.startPositionMs shouldBe 50_000L
                    span.currentPositionMs shouldBe 50_000L
                }
            }
        }

        // ── F6: recoverOrphan must never finalize the current process's live span ────────────────

        test("recoverOrphan does not finalize the current process's live span") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }, processId = "process-current") { recorder, db, enqueuedOps ->
                    // THIS process opens a live span and extends it.
                    recorder.onPlay(BOOK_ID, START_POSITION, SPEED)
                    now = 31_000L
                    recorder.onPeriodicTick(5_000L)

                    // A late startup recovery fires while this process is mid-listen (the race the
                    // reorder + process identity guards against).
                    recorder.recoverOrphan()

                    // The live span is untouched — not finalized, truncated, or deleted.
                    val span = db.tentativeSpanDao().get()
                    span.shouldNotBeNull()
                    span.processId shouldBe "process-current"
                    span.currentPositionMs shouldBe 5_000L
                    db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID).size shouldBe 0
                    enqueuedOps.size shouldBe 0
                }
            }
        }

        // ── F8: a seek splits the span so a jumped-over range isn't fabricated as listened ───────

        test("onSeek splits the span so a jumped-over range is not counted as listened content") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, enqueuedOps ->
                    recorder.onPlay(BOOK_ID, positionMs = 600_000L, SPEED) // 0:10:00
                    now = 121_000L
                    recorder.onPeriodicTick(720_000L) // 0:12:00
                    now = 122_000L
                    recorder.onSeek(positionBeforeSeek = 720_000L, positionAfterSeek = 18_000_000L) // → 5:00:00
                    now = 242_000L
                    recorder.onPause(positionMs = 18_120_000L) // 5:02:00

                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 2

                    // Pre-seek span 0:10:00 → 0:12:00 — NOT inflated across the jump.
                    val first = events.first { it.startPositionMs == 600_000L }
                    first.endPositionMs shouldBe 720_000L

                    // Post-seek span 5:00:00 → 5:02:00.
                    val second = events.first { it.startPositionMs == 18_000_000L }
                    second.endPositionMs shouldBe 18_120_000L

                    // The jumped-over range (0:12:00 .. 5:00:00) is covered by NEITHER span.
                    events.none { it.startPositionMs < 18_000_000L && it.endPositionMs > 720_000L } shouldBe true

                    enqueuedOps.size shouldBe 2
                }
            }
        }

        // ── F8: a reverse seek must never produce an inverted span ───────────────────────────────

        test("onSeek clamps a stale-backward before-position so the pre-seek span is never inverted") {
            runTest {
                var now = 1_000L
                withFixture(nowMillisProvider = { now }) { recorder, db, _ ->
                    recorder.onPlay(BOOK_ID, positionMs = 720_000L, SPEED) // span starts at 0:12:00
                    now = 2_000L
                    // A backward seek reported with a before-position BEHIND the span's own start.
                    // Pre-fix this finalized endPositionMs=700_000 < startPositionMs=720_000 — an
                    // inverted span with undefined server handling.
                    recorder.onSeek(positionBeforeSeek = 700_000L, positionAfterSeek = 60_000L)

                    val events = db.listeningEventDao().getByBookForUser(USER_ID, BOOK_ID)
                    events.size shouldBe 1
                    val span = events[0]
                    (span.endPositionMs >= span.startPositionMs) shouldBe true
                    span.endPositionMs shouldBe 720_000L // clamped up to the span's start
                }
            }
        }
    })

// ── Fixture helpers ──────────────────────────────────────────────────────────

/** Captured fields from a single [enqueue] call. */
internal data class CapturedEnqueue(
    val entityId: String,
    val payload: String,
    val ownerUserId: String,
)

/** Run [block] with a recorder wired to a fixed [nowMillis] timestamp. */
internal suspend fun withFixture(
    nowMillis: Long,
    block: suspend (
        ListeningEventRecorder,
        com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
        List<CapturedEnqueue>,
    ) -> Unit,
) = withFixture(nowMillisProvider = { nowMillis }, block = block)

internal suspend fun withFixture(
    nowMillisProvider: () -> Long,
    failEnqueue: Boolean = false,
    processId: String = "process-fixture-default",
    block: suspend (
        ListeningEventRecorder,
        com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
        List<CapturedEnqueue>,
    ) -> Unit,
) {
    val db = createInMemoryTestDatabase()
    try {
        val captured = mutableListOf<CapturedEnqueue>()

        // A real PendingOperationQueue is used so the DAO write runs too; we also
        // capture calls for assertion via the enqueue lambda indirection.
        val realQueue =
            PendingOperationQueue(
                dao = db.pendingOperationV2Dao(),
                sender = PendingOperationSender { AppResult.Success(Unit) },
                nowMillis = nowMillisProvider,
            )

        val recorder =
            ListeningEventRecorder(
                listeningEventDao = db.listeningEventDao(),
                tentativeSpanDao = db.tentativeSpanDao(),
                transactionRunner = RoomTransactionRunner(db),
                enqueue = { entityId, payload, ownerUserId ->
                    if (failEnqueue) error("Simulated outbox enqueue failure")
                    captured.add(CapturedEnqueue(entityId, payload, ownerUserId))
                    realQueue.enqueue(OutboxChannels.ListeningEvents, entityId, OpKind.Upsert, payload, ownerUserId)
                },
                currentUserId = { USER_ID },
                deviceInfo = DeviceInfoProvider { DeviceInfo(deviceName = DEVICE_LABEL) },
                processId = processId,
                clock =
                    object : Clock {
                        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillisProvider())
                    },
                timeZone = { TimeZone.currentSystemDefault() },
            )

        block(recorder, db, captured)
    } finally {
        db.close()
    }
}

// Readability alias so test code reads `recorder.onPlay(..., oldSpeed = 1.0f)`
private suspend fun ListeningEventRecorder.onPlay(
    bookId: String,
    positionMs: Long,
    oldSpeed: Float,
) = onPlay(bookId, positionMs, oldSpeed)
