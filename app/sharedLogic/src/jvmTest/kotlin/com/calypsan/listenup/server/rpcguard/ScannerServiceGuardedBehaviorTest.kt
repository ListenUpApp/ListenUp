package com.calypsan.listenup.server.rpcguard

import app.cash.turbine.test
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.LibraryId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class ScannerServiceGuardedBehaviorTest :
    FunSpec({

        test("Flow that throws RuntimeException emits Data, then RpcEvent.Error, then completes") {
            val startedEvent = ScanEvent.Started(correlationId = "test-cid", libraryId = LibraryId("lib-1"), rootPath = "/audiobooks")
            val brokenFlow =
                flow<RpcEvent<ScanEvent>> {
                    emit(RpcEvent.Data(startedEvent))
                    throw RuntimeException("boom")
                }
            val delegate = mock<ScannerService>()
            every { delegate.observeProgress() } returns brokenFlow
            val guard = ScannerServiceGuarded(delegate)

            runTest {
                guard.observeProgress().test {
                    val first = awaitItem()
                    first.shouldBeInstanceOf<RpcEvent.Data<ScanEvent>>()
                    val errorEvent = awaitItem()
                    errorEvent.shouldBeInstanceOf<RpcEvent.Error>()
                    val internalError = errorEvent.error.shouldBeInstanceOf<InternalError>()
                    // The server exception's class name and message must NOT cross the wire — the
                    // full detail stays in the server log, keyed by the correlation id.
                    internalError.cause shouldBe null
                    internalError.debugInfo shouldBe null
                    awaitComplete()
                }
            }
        }

        test("Flow that throws CancellationException propagates cancellation") {
            val cancelledFlow =
                flow<RpcEvent<ScanEvent>> {
                    throw CancellationException("stop")
                }
            val delegate = mock<ScannerService>()
            every { delegate.observeProgress() } returns cancelledFlow
            val guard = ScannerServiceGuarded(delegate)

            runTest {
                guard.observeProgress().test {
                    awaitError().shouldBeInstanceOf<CancellationException>()
                }
            }
        }
    })
