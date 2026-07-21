@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.RpcDispatch
import com.calypsan.listenup.client.data.remote.RpcPolicy
import com.calypsan.listenup.client.data.remote.forTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration

/** The stream-open hello / liveness heartbeat, exactly as the server emits it. */
internal fun heartbeatFrame(): SyncFrame =
    SyncFrame(
        domain = SyncFrame.CONTROL,
        revision = null,
        json = contractJson.encodeToString(SyncControl.serializer(), SyncControl.Heartbeat),
    )

internal fun dataFrame(revision: Long): SyncFrame = SyncFrame(domain = "tags", revision = revision, json = "{}")

class RpcSyncStreamClientTest :
    FunSpec({
        test("reconnectNow wakes the backoff wait and resets the delay") {
            val scope = TestScope(StandardTestDispatcher())
            var attempts = 0
            val service =
                object : SyncStreamService {
                    override fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>> =
                        flow {
                            attempts++
                            error("boom") // always fail → drives the reconnect backoff path
                        }
                }
            val client =
                RpcSyncStreamClient(
                    channel = RpcChannel.forTest(service),
                    state = SyncEngineState(),
                    scope = scope,
                    nowMillis = { 0L },
                )

            client.connect()
            scope.testScheduler.runCurrent()
            val afterFirst = attempts
            afterFirst shouldBeGreaterThan 0

            scope.testScheduler.advanceTimeBy(500)
            scope.testScheduler.runCurrent()
            attempts shouldBe afterFirst // still waiting out the backoff

            client.reconnectNow()
            scope.testScheduler.runCurrent()
            attempts shouldBe afterFirst + 1 // woke immediately, exactly one more attempt (no busy-loop)

            client.disconnect()
        }

        test("lastEventId does not advance past a frame whose delivery never completed") {
            val scope = TestScope(StandardTestDispatcher())
            val service =
                object : SyncStreamService {
                    override fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>> =
                        flow {
                            emit(RpcEvent.Data(heartbeatFrame())) // hello — swallowed, latches Connected
                            emit(RpcEvent.Data(dataFrame(1L)))
                            emit(RpcEvent.Data(dataFrame(2L)))
                            awaitCancellation()
                        }
                }
            val client =
                RpcSyncStreamClient(
                    channel = RpcChannel.forTest(service),
                    state = SyncEngineState(),
                    scope = scope,
                    nowMillis = { 0L },
                    // Zero buffer: frame 2's emit has nowhere to land once the one collector is
                    // stalled processing frame 1, so it suspends deterministically.
                    frameBufferCapacity = 0,
                )

            // Consumes exactly one frame, then never returns to ask for the next — what a
            // downstream consumer that stops draining looks like from the frame bus's side.
            val collectorJob = scope.launch { client.frames.collect { awaitCancellation() } }
            scope.testScheduler.runCurrent()

            client.connect()
            scope.testScheduler.runCurrent()

            // Frame 1 was handed to the collector; frame 2's emit is still suspended.
            client.currentLastEventId() shouldBe 1L

            // The engine tearing the connection down while frame 2's emit is suspended — the
            // exact scenario the ordering contract protects: frame 2 was never delivered, so the
            // resume cursor must not have moved past it.
            client.disconnect()
            scope.testScheduler.runCurrent()

            client.currentLastEventId() shouldBe 1L

            collectorJob.cancel()
        }

        test("read-idle watchdog: heartbeats keep the stream alive; silence past the bound resubscribes") {
            val scope = TestScope(StandardTestDispatcher())
            var subscriptions = 0
            val service =
                object : SyncStreamService {
                    override fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>> =
                        flow {
                            subscriptions++
                            emit(RpcEvent.Data(heartbeatFrame())) // hello
                            if (subscriptions == 1) {
                                delay(60_000)
                                emit(RpcEvent.Data(heartbeatFrame())) // one late heartbeat, then silence
                            }
                            awaitCancellation()
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

            client.connect()
            scope.testScheduler.runCurrent()
            subscriptions shouldBe 1
            state.value.connection.shouldBeInstanceOf<ConnectionState.Connected>()

            // t=70s: the t=60s heartbeat reset the 75s window — still connected, no resubscribe.
            scope.testScheduler.advanceTimeBy(70_000)
            scope.testScheduler.runCurrent()
            subscriptions shouldBe 1
            state.value.connection.shouldBeInstanceOf<ConnectionState.Connected>()

            // t=140s: silence since t=60s tripped the watchdog at t=135s; after the 1s backoff
            // the loop resubscribed at t=136s.
            scope.testScheduler.advanceTimeBy(70_000)
            scope.testScheduler.runCurrent()
            subscriptions shouldBe 2

            client.disconnect()
        }

        test("watchdog trip invalidates the channel generation before resubscribing") {
            val scope = TestScope(StandardTestDispatcher())
            var subscriptions = 0
            var invalidations = 0
            var invalidationsAtSecondSubscribe = -1
            val service =
                object : SyncStreamService {
                    override fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>> =
                        flow {
                            subscriptions++
                            if (subscriptions == 2) invalidationsAtSecondSubscribe = invalidations
                            emit(RpcEvent.Data(heartbeatFrame())) // hello
                            awaitCancellation() // then silence — a half-open socket
                        }
                }
            val dispatch =
                object : RpcDispatch<SyncStreamService> {
                    override suspend fun <R> call(
                        timeout: Duration,
                        idempotent: Boolean,
                        block: suspend (SyncStreamService) -> R,
                    ): R = block(service)

                    override fun <R> streaming(subscribe: suspend (SyncStreamService) -> Flow<R>): Flow<R> = flow { emitAll(subscribe(service)) }

                    override suspend fun invalidate() {
                        invalidations++
                    }
                }
            val client =
                RpcSyncStreamClient(
                    channel = RpcChannel(dispatch, RpcPolicy.Authed),
                    state = SyncEngineState(),
                    scope = scope,
                    nowMillis = { 0L },
                )

            client.connect()
            scope.testScheduler.runCurrent()
            subscriptions shouldBe 1

            // Silence past the 75s watchdog + the 1s backoff: the loop resubscribes — but a
            // watchdog cancellation looks like a caller cancel to RpcProxyCache, which never
            // invalidates on those. The client must invalidate explicitly, BEFORE the next
            // subscribe, so the fresh lease dials a fresh connection instead of re-leasing
            // the half-open one (else recovery rides on the WS ping layer alone).
            scope.testScheduler.advanceTimeBy(80_000)
            scope.testScheduler.runCurrent()
            subscriptions shouldBe 2
            invalidations shouldBe 1
            invalidationsAtSecondSubscribe shouldBe 1

            client.disconnect()
            scope.testScheduler.runCurrent()
        }
    })
