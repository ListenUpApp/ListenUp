package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.SyncError
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class SyncEngineStateTest :
    FunSpec({

        test("initial state is Disconnected, no errors, queue empty") {
            val state = SyncEngineState()
            val snapshot = state.value
            snapshot.connection shouldBe ConnectionState.Disconnected(reason = null)
            snapshot.recentErrorCount shouldBe 0
            snapshot.pendingQueueDepth shouldBe 0
            snapshot.deadLetterCount shouldBe 0
            snapshot.meaningfulErrorActive shouldBe false
            snapshot.lastSuccessAtMillis shouldBe null
        }

        test("connection state transitions update the flow") {
            runTest {
                val state = SyncEngineState()
                state.setConnection(ConnectionState.Connecting)
                state.value.connection shouldBe ConnectionState.Connecting
                state.setConnection(ConnectionState.Connected(lastEventId = 42L))
                state.value.connection shouldBe ConnectionState.Connected(lastEventId = 42L)
            }
        }

        test("meaningfulErrorActive flips true after errorCount >= 5") {
            val state = SyncEngineState()
            repeat(4) { state.recordError(SyncError.RealtimeDisconnected()) }
            state.value.meaningfulErrorActive shouldBe false
            state.recordError(SyncError.RealtimeDisconnected())
            state.value.meaningfulErrorActive shouldBe true
        }

        test("recordSuccess(now) clears errorCount and timestamps lastSuccess") {
            val state = SyncEngineState()
            repeat(5) { state.recordError(SyncError.RealtimeDisconnected()) }
            state.value.meaningfulErrorActive shouldBe true
            state.recordSuccess(nowMillis = 1_000_000L)
            state.value.recentErrorCount shouldBe 0
            state.value.meaningfulErrorActive shouldBe false
            state.value.lastSuccessAtMillis shouldBe 1_000_000L
        }

        test("evaluateMeaningfulError(now) flips true when secondsSinceLastSuccess >= 60") {
            val state = SyncEngineState()
            state.recordSuccess(nowMillis = 0L)
            state.value.meaningfulErrorActive shouldBe false
            state.evaluateMeaningfulError(nowMillis = 59_000L)
            state.value.meaningfulErrorActive shouldBe false
            state.evaluateMeaningfulError(nowMillis = 60_000L)
            state.value.meaningfulErrorActive shouldBe true
        }

        test("setQueueDepth and setDeadLetterCount update independently") {
            val state = SyncEngineState()
            state.setQueueDepth(7)
            state.setDeadLetterCount(2)
            state.value.pendingQueueDepth shouldBe 7
            state.value.deadLetterCount shouldBe 2
        }
    })
