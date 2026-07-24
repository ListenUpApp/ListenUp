package com.calypsan.listenup.client.core

import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException

class FlowExtensionsTest :
    FunSpec({
        test("fallbackTo emits the recovered value when upstream throws") {
            val failing = flow<Int> { throw IllegalStateException("boom") }
            runTest {
                failing.fallbackTo { -1 }.test {
                    awaitItem() shouldBe -1
                    awaitComplete()
                }
            }
        }

        test("fallbackTo passes the throwable to recover") {
            val failing = flow<String> { throw IllegalStateException("boom") }
            runTest {
                failing.fallbackTo { it.message ?: "none" }.test {
                    awaitItem() shouldBe "boom"
                    awaitComplete()
                }
            }
        }

        test("fallbackTo does not interfere with a successful flow") {
            runTest {
                flowOf(1, 2, 3).fallbackTo { -1 }.test {
                    awaitItem() shouldBe 1
                    awaitItem() shouldBe 2
                    awaitItem() shouldBe 3
                    awaitComplete()
                }
            }
        }

        test("fallbackTo rethrows CancellationException instead of emitting fallback") {
            // Collected directly rather than through Turbine: the invariant under test is
            // that CancellationException propagates out of the flow (preserving structured
            // concurrency) instead of being recovered into a fallback emission. Turbine
            // intercepts the throw as an Error event, which masks that propagation.
            val cancelling = flow<Int> { throw CancellationException("cancelled") }
            runTest {
                var thrown: Throwable? = null
                val emitted = mutableListOf<Int>()
                try {
                    cancelling.fallbackTo { -1 }.collect { emitted += it }
                } catch (e: CancellationException) {
                    thrown = e
                }
                (thrown is CancellationException) shouldBe true
                emitted shouldBe emptyList()
            }
        }
    })
