package com.calypsan.listenup.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

private class Transient : Exception()

private class NotTransient : Exception()

class RetryOnTransientTest :
    FunSpec({

        test("returns the value on first success without retrying") {
            runBlocking {
                var calls = 0
                val result =
                    retryOnTransient(maxAttempts = 3, isTransient = { true }) {
                        calls++
                        "value"
                    }
                result shouldBe "value"
                calls shouldBe 1
            }
        }

        test("retries a transient failure and returns once it succeeds") {
            runBlocking {
                var calls = 0
                val retried = mutableListOf<Int>()
                val result =
                    retryOnTransient(
                        maxAttempts = 3,
                        isTransient = { it is Transient },
                        onRetry = { attempt, _ -> retried += attempt },
                    ) {
                        calls++
                        if (calls < 3) throw Transient()
                        "recovered"
                    }
                result shouldBe "recovered"
                calls shouldBe 3
                retried shouldBe listOf(0, 1)
            }
        }

        test("propagates a non-transient failure immediately without retrying") {
            runBlocking {
                var calls = 0
                shouldThrow<NotTransient> {
                    retryOnTransient(maxAttempts = 3, isTransient = { it is Transient }) {
                        calls++
                        throw NotTransient()
                    }
                }
                calls shouldBe 1
            }
        }

        test("throws the last transient failure once attempts are exhausted") {
            runBlocking {
                var calls = 0
                shouldThrow<Transient> {
                    retryOnTransient(maxAttempts = 3, isTransient = { it is Transient }) {
                        calls++
                        throw Transient()
                    }
                }
                calls shouldBe 3
            }
        }

        test("re-throws CancellationException without retrying or classifying it") {
            runBlocking {
                var calls = 0
                shouldThrow<CancellationException> {
                    retryOnTransient(maxAttempts = 3, isTransient = { true }) {
                        calls++
                        throw CancellationException("cancelled")
                    }
                }
                calls shouldBe 1
            }
        }
    })
