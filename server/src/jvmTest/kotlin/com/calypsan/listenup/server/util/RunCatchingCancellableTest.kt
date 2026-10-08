package com.calypsan.listenup.server.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import io.kotest.assertions.throwables.shouldThrow

class RunCatchingCancellableTest :
    FunSpec({
        test("returns success for a normal return") {
            runTest { runCatchingCancellable { 42 }.getOrNull() shouldBe 42 }
        }

        test("returns failure for a thrown Exception") {
            runTest {
                val result = runCatchingCancellable { error("boom") }
                result.isFailure shouldBe true
            }
        }

        test("rethrows CancellationException instead of capturing it") {
            runTest {
                shouldThrow<CancellationException> {
                    runCatchingCancellable { throw CancellationException("cancelled") }
                }
            }
        }
    })
