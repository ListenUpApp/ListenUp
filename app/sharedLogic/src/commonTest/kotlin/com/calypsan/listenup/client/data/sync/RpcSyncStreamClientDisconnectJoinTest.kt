@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A reconnect must never run two firehose subscription loops at once.
 *
 * [RpcSyncStreamClient.disconnect] is fire-and-forget: `Job.cancel()` returns before the loop's
 * body has observed the cancellation, and `connect()`'s "already running" guard reads
 * `Job.isActive` — already false for a cancelled-but-unfinished job. So `disconnect(); connect()`
 * back-to-back (the shape of `SyncEngine.reconnect()`, the CursorStale recovery, and the auth
 * gate's re-login edge) launched loop #2 while loop #1 was still live: both wrote the same
 * `lastEventId` and emitted into the same frame bus — a client-side reorder/duplicate-replay source
 * on every reconnect. [SyncStreamClient.disconnectAndJoin] closes that window by suspending until the
 * previous loop has finished.
 */
class RpcSyncStreamClientDisconnectJoinTest :
    FunSpec({
        test("disconnectAndJoin tears the loop down before a reconnect opens the next one") {
            runTest {
                var live = 0
                var maxLive = 0
                val subscriptions = mutableListOf<Long?>()
                val service =
                    object : FakeSyncStreamService() {
                        override fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>> =
                            flow {
                                subscriptions += sinceRevision
                                live++
                                maxLive = maxOf(maxLive, live)
                                try {
                                    emit(RpcEvent.Data(heartbeatFrame()))
                                    emit(RpcEvent.Data(dataFrame(1L)))
                                    awaitCancellation()
                                } finally {
                                    live--
                                }
                            }
                    }
                val state = SyncEngineState()
                val client =
                    RpcSyncStreamClient(
                        channel = RpcChannel.forTest(service),
                        state = state,
                        scope = backgroundScope,
                        nowMillis = { 0L },
                    )
                val framesJob = backgroundScope.launch { client.frames.collect { } }
                runCurrent()

                client.connect()
                runCurrent()
                live shouldBe 1

                // The reconnect shape from SyncEngine.reconnect(): tear down, then re-open.
                client.disconnectAndJoin()
                // RED before RpcSyncStreamClient overrides disconnectAndJoin: the default delegates to
                // the non-joining disconnect, so the cancelled loop's finally has not run yet.
                live shouldBe 0
                client.connect()
                runCurrent()

                live shouldBe 1
                maxLive shouldBe 1 // never two loops at once
                subscriptions.size shouldBe 2

                client.disconnect()
                framesJob.cancel()
            }
        }
    })
