package com.calypsan.listenup.server.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException

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
                var rethrown = false
                try {
                    runCatchingCancellable { throw CancellationException("cancelled") }
                } catch (e: CancellationException) {
                    rethrown = true
                }
                rethrown shouldBe true
            }
        }
    })
