package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

/**
 * 010-A regression: [ListeningEventRecorder] enqueues the finalized span's outbox op INSIDE the
 * finalize transaction, so it must NOT signal the drain from within `enqueue` (a pre-commit tick
 * wakes the drain against WAL state that can't see the new row yet, stranding the op). The tick is
 * deferred to `signalEnqueued`, fired only after the transaction commits — mirroring
 * [com.calypsan.listenup.client.data.sync.OfflineEditor]'s canonical pattern.
 */
class ListeningEventRecorderSignalTimingTest :
    FunSpec({

        test("the drain signal fires only AFTER the finalize transaction commits, and a drain then sees the op") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val queue =
                        PendingOperationQueue(
                            dao = db.pendingOperationV2Dao(),
                            sender = PendingOperationSender { AppResult.Success(Unit) },
                        )
                    val initialSignal = queue.observeEnqueueSignal().value

                    // A runner that snapshots the signal DURING the transaction — after the recorder's
                    // writes (event upsert + signal=false enqueue + tentative delete), before commit.
                    // With the fix this equals the initial value; the bug ticked inside enqueue, so it
                    // would already be +1 here.
                    var signalDuringTx: Long? = null
                    val spyRunner =
                        object : TransactionRunner {
                            override suspend fun <R> atomically(block: suspend () -> R): R {
                                val result = block()
                                signalDuringTx = queue.observeEnqueueSignal().value
                                return result
                            }
                        }

                    var nowMs = 1_000L
                    val recorder =
                        ListeningEventRecorder(
                            listeningEventDao = db.listeningEventDao(),
                            tentativeSpanDao = db.tentativeSpanDao(),
                            transactionRunner = spyRunner,
                            enqueue = { entityId, payload, ownerUserId ->
                                queue.enqueue(
                                    OutboxChannels.ListeningEvents,
                                    entityId,
                                    OpKind.Upsert,
                                    payload,
                                    ownerUserId,
                                    signal = false,
                                )
                            },
                            signalEnqueued = { queue.signalEnqueued() },
                            currentUserId = { "u1" },
                            deviceInfo = DeviceInfoProvider { DeviceInfo(deviceName = "test-device") },
                            clock =
                                object : Clock {
                                    override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
                                },
                            timeZone = { TimeZone.currentSystemDefault() },
                        )

                    recorder.onPlay(bookId = "book1", positionMs = 0L, playbackSpeed = 1.0f)
                    nowMs = 2_000L // advance so the span is non-zero-duration and finalizes
                    recorder.onPause(positionMs = 5_000L)

                    // No pre-commit tick: neither the signal=false enqueue nor the post-commit
                    // signalEnqueued ticked while the transaction was open.
                    signalDuringTx shouldBe initialSignal
                    // The signal fired exactly once, post-commit.
                    queue.observeEnqueueSignal().value shouldBe initialSignal + 1
                    // A drain triggered by that signal now finds the committed op.
                    queue.drain().sent shouldBe 1
                } finally {
                    db.close()
                }
            }
        }
    })
