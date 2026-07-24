package io.kotest.provided

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the heavyweight-retry net's coverage predicate.
 *
 * The whole point of [HeavyweightE2ERetryExtension] is which specs it retries; that decision lives
 * in [retriesForFlakiness]. Its predecessor matched only the `*E2ETest`/`*EndToEndTest` suffix,
 * while every historically-flaky real-thread sync spec is named plain `*Test` — so the safety net
 * pointed away from the flakes. This test locks in that the named real-thread specs are covered and
 * that an ordinary spec is not (so the net can't silently swallow a genuinely-broken unit test).
 */
class RetryCoverageTest :
    FunSpec({
        test("covers every named real-thread flaky spec") {
            RETRY_COVERED_NAMES.forEach { name ->
                retriesForFlakiness(name) shouldBe true
            }
        }

        test("covers the E2E suffixes") {
            retriesForFlakiness("BackupUploadRestoreE2ETest") shouldBe true
            retriesForFlakiness("SomethingEndToEndTest") shouldBe true
        }

        test("does not cover an ordinary spec") {
            // A plain unit test must NOT be retried — retrying it would mask a real regression.
            retriesForFlakiness("SearchRepositoryImplTest") shouldBe false
            retriesForFlakiness("ChapterMathTest") shouldBe false
        }
    })
