package io.kotest.provided

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the poisoned-victim predicate of [FlakyServerSpecRetryExtension] — the `:server` twin of the
 * `:app:sharedLogic` coverage test. A test failing with kotlinx-coroutines-test's
 * `UncaughtExceptionsBeforeTest` never ran its own body (a prior test leaked an uncaught background
 * exception), so any leaf may be retried on it; an ordinary failure must never match.
 */
class RetryCoverageTest :
    FunSpec({
        test("retries any test poisoned by a prior test's leaked background exception") {
            // The class is Kotlin-internal to kotlinx-coroutines-test, hence reflection.
            val poisoning =
                Class
                    .forName("kotlinx.coroutines.test.UncaughtExceptionsBeforeTest")
                    .getDeclaredConstructor()
                    .newInstance() as Throwable
            retriesForPoisoning(poisoning) shouldBe true
        }

        test("does not treat an ordinary failure as poisoning") {
            retriesForPoisoning(null) shouldBe false
            // UncaughtExceptionsBeforeTest extends IllegalStateException — the supertype must not match.
            retriesForPoisoning(IllegalStateException("boom")) shouldBe false
            retriesForPoisoning(AssertionError("expected 1 but was 2")) shouldBe false
        }
    })
