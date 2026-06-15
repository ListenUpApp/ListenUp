package com.calypsan.listenup.client.testinfra

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger

/**
 * Proves the project-level heavyweight-E2E retry extension (in `io.kotest.provided.ProjectConfig`)
 * actually re-executes a transiently-failing leaf test.
 *
 * This spec's name ends in `E2ETest`, so the extension is in scope. The body fails on its **first**
 * invocation and passes on the **second**; the only way the test reports green is if the extension
 * caught the first failure and re-ran the body. If the retry mechanism ever regresses (removed,
 * mis-scoped, attempt budget < 2), this probe goes red — it is the regression guard for the retry
 * feature itself. Coverage is unchanged: a test that fails *every* attempt still fails.
 */
class RetryExtensionProbeE2ETest :
    FunSpec({
        test("a transient first-attempt failure is retried to success") {
            val attempt = attempts.incrementAndGet()
            // Green only on the retry — the first attempt asserts false on purpose.
            (attempt >= 2) shouldBe true
        }
    }) {
    private companion object {
        val attempts = AtomicInteger(0)
    }
}
