package com.calypsan.listenup.client.testinfra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves the project-level heavyweight-E2E retry extension (in `io.kotest.provided.ProjectConfig`)
 * actually re-executes a transiently-failing leaf test **and** records that retry in the
 * machine-readable ledger.
 *
 * This spec's name ends in `E2ETest`, so the extension is in scope. The first test's body fails on
 * its **first** invocation and passes on the **second**; the only way it reports green is if the
 * extension caught the first failure and re-ran the body. The second test then asserts that the
 * retry left a line in `build/e2e-retries.log` carrying the spec, the test name, `attempt=2/3`, and
 * the original failure — so the ledger (surfaced in CI and the input for future de-flaking) can
 * never silently stop recording. If the retry mechanism ever regresses (removed, mis-scoped, attempt
 * budget < 2) or stops writing the ledger, this probe goes red — it is the regression guard for the
 * retry feature itself. Coverage is unchanged: a test that fails *every* attempt still fails.
 */
class RetryExtensionProbeE2ETest :
    FunSpec({
        test("a transient first-attempt failure is retried to success") {
            val attempt = attempts.incrementAndGet()
            // Green only on the retry — the first attempt asserts false on purpose.
            (attempt >= 2) shouldBe true
        }

        test("the retry is recorded in the machine-readable ledger") {
            // Written by HeavyweightE2ERetryExtension at retry time; resolve the path via the same
            // Gradle-provided `listenup.e2eRetryLedger` system property the extension uses, so this
            // is independent of the Test task's workingDir.
            val ledger = File(System.getProperty("listenup.e2eRetryLedger") ?: "build/e2e-retries.log")
            ledger.exists() shouldBe true
            val probeLines = ledger.readLines().filter { it.contains("RetryExtensionProbeE2ETest") }
            probeLines.shouldNotBeEmpty()
            val entry = probeLines.first()
            entry shouldContain "a transient first-attempt failure is retried to success"
            entry shouldContain "attempt=2/3"
            // The ledger must carry the ORIGINAL failure, not just the fact of a retry.
            // Kotest's shouldBe throws org.opentest4j.AssertionFailedError.
            entry shouldContain "AssertionFailedError"
        }
    }) {
    private companion object {
        val attempts = AtomicInteger(0)
    }
}
