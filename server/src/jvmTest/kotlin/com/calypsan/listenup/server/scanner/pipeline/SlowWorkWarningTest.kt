package com.calypsan.listenup.server.scanner.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes

class SlowWorkWarningTest :
    FunSpec({

        test("a block still running after the threshold warns once and its value is returned") {
            runTest {
                var warnings = 0

                val result =
                    warningIfSlow(after = 2.minutes, warn = { warnings++ }) {
                        delay(3.minutes)
                        "done"
                    }

                result shouldBe "done"
                warnings shouldBe 1
            }
        }

        test("a block that finishes immediately never warns") {
            runTest {
                var warnings = 0

                val result = warningIfSlow(after = 2.minutes, warn = { warnings++ }) { 42 }
                advanceUntilIdle()

                result shouldBe 42
                warnings shouldBe 0
            }
        }

        test("a block that throws propagates the exception and does not warn afterwards") {
            runTest {
                var warnings = 0

                shouldThrow<IllegalStateException> {
                    warningIfSlow(after = 2.minutes, warn = { warnings++ }) { error("boom") }
                }
                advanceUntilIdle()

                warnings shouldBe 0
            }
        }
    })
