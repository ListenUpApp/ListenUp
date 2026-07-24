@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * The resubscribe simulation the firehose migration hinges on: a drop after the first event
 * yields one [RpcEvent.Error] then completion (see [RpcChannel.stream]), and it is THIS client's
 * loop — not the channel — that resubscribes, resuming from the revision it had advanced to.
 * Proves no frame is lost or duplicated across drops, the connection state walks the full
 * Connecting → Connected → Disconnected → Connecting → Connected cycle (sampled at the loop's
 * deterministic suspension points — [SyncEngineState] is a conflating StateFlow, so a transition
 * log cannot observe transient states reliably), the backoff ladder is honored under virtual
 * time, and [RpcSyncStreamClient.reconnectNow] short-circuits the wait.
 */
class RpcSyncStreamClientResubscribeTest :
    FunSpec({
        test("drops resubscribe with the advanced cursor: no frame lost or duplicated") {
            val scope = TestScope(StandardTestDispatcher())
            val subscriptions = mutableListOf<Long?>()
            val service =
                object : SyncStreamService {
                    override fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>> =
                        flow {
                            subscriptions += sinceRevision
                            when (subscriptions.size) {
                                1 -> {
                                    emit(RpcEvent.Data(heartbeatFrame())) // hello
                                    emit(RpcEvent.Data(dataFrame(1L)))
                                    emit(RpcEvent.Data(dataFrame(2L)))
                                    error("mid-stream drop")
                                }

                                2 -> {
                                    emit(RpcEvent.Data(heartbeatFrame()))
                                    emit(RpcEvent.Data(dataFrame(3L)))
                                    emit(RpcEvent.Data(dataFrame(4L)))
                                    error("second drop")
                                }

                                else -> {
                                    // Hold the hello back so the test can observe Connecting.
                                    delay(10)
                                    emit(RpcEvent.Data(heartbeatFrame()))
                                    emit(RpcEvent.Data(dataFrame(5L)))
                                    awaitCancellation() // stays live
                                }
                            }
                        }
                }
            val state = SyncEngineState()
            val client =
                RpcSyncStreamClient(
                    channel = RpcChannel.forTest(service),
                    state = state,
                    scope = scope,
                    nowMillis = { 0L },
                )

            val received = mutableListOf<SyncFrame>()
            val framesJob = scope.launch { client.frames.collect { received += it } }
            scope.testScheduler.runCurrent()

            state.value.connection shouldBe ConnectionState.Disconnected(reason = null)

            // Subscription 1: hello + frames 1, 2, then a mid-stream drop → Disconnected.
            client.connect()
            scope.testScheduler.runCurrent()
            subscriptions shouldContainExactly listOf(null)
            received.map { it.revision } shouldContainExactly listOf(1L, 2L)
            state.value.connection shouldBe ConnectionState.Disconnected("reconnecting")

            // Backoff honored: 1ms short of the 1s ladder step, no resubscribe yet.
            scope.testScheduler.advanceTimeBy(999)
            scope.testScheduler.runCurrent()
            subscriptions.size shouldBe 1

            // Subscription 2 fires at the 1s mark — with the ADVANCED cursor, not the seed —
            // delivers 3, 4, drops again.
            scope.testScheduler.advanceTimeBy(1)
            scope.testScheduler.runCurrent()
            subscriptions shouldContainExactly listOf(null, 2L)
            received.map { it.revision } shouldContainExactly listOf(1L, 2L, 3L, 4L)
            state.value.connection shouldBe ConnectionState.Disconnected("reconnecting")

            // reconnectNow() short-circuits the second backoff wait entirely: subscription 3 is
            // already in flight (Connecting) with the cursor advanced to 4.
            client.reconnectNow()
            scope.testScheduler.runCurrent()
            subscriptions shouldContainExactly listOf(null, 2L, 4L)
            state.value.connection shouldBe ConnectionState.Connecting

            // The held-back hello arrives: Connected latches, frame 5 lands exactly once.
            scope.testScheduler.advanceTimeBy(10)
            scope.testScheduler.runCurrent()
            received.map { it.revision } shouldContainExactly listOf(1L, 2L, 3L, 4L, 5L)
            state.value.connection shouldBe ConnectionState.Connected(5L)

            // Heartbeats were swallowed — only data frames crossed the bus.
            received.none { it.domain == SyncFrame.CONTROL } shouldBe true

            client.disconnect()
            framesJob.cancel()
        }
    })
