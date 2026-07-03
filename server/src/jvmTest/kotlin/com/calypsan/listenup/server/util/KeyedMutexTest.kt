package com.calypsan.listenup.server.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** Unit coverage for [KeyedMutex]'s same-key exclusion, cross-key independence, and passthrough. */
class KeyedMutexTest :
    FunSpec({

        test("two concurrent withLock calls for the same key never overlap") {
            runTest {
                val lock = KeyedMutex()
                var active = 0
                var maxObservedConcurrency = 0

                coroutineScope {
                    repeat(5) {
                        launch {
                            lock.withLock("k") {
                                active++
                                maxObservedConcurrency = maxOf(maxObservedConcurrency, active)
                                delay(10)
                                active--
                            }
                        }
                    }
                }

                maxObservedConcurrency shouldBe 1
            }
        }

        test("withLock for different keys does not block each other") {
            runTest {
                val lock = KeyedMutex()
                var aRan = false
                var bRan = false

                coroutineScope {
                    launch { lock.withLock("a") { aRan = true } }
                    launch { lock.withLock("b") { bRan = true } }
                }

                aRan shouldBe true
                bRan shouldBe true
            }
        }

        test("withLock returns the value produced by block") {
            runTest {
                val lock = KeyedMutex()
                val result = lock.withLock("k") { 42 }
                result shouldBe 42
            }
        }
    })
