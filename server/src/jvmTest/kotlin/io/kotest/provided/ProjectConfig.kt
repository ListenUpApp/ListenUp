package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import io.kotest.engine.test.TestResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Kotest project configuration for the **`:server`** JVM test run (auto-discovered by Kotest as
 * `io.kotest.provided.ProjectConfig` on the server test classpath — distinct from the `:sharedLogic`
 * config, which is not on this module's classpath).
 *
 * The server suite boots an in-process Ktor server + a real SQLite DB per spec. On the contended
 * 2-core CI runner a few timing-sensitive specs intermittently stall or fail in ways that never
 * reproduce on a fast local box — historically the `Test (JVM)` job's recurring hang. Two guards,
 * mirroring the proven `:sharedLogic` config:
 *
 *  - [timeout] — a generous per-test ceiling so a single deadlocked test (e.g. a DB-pool drain that
 *    blocks on `SQLITE_BUSY`) **fails fast with a named "timed out" error** instead of stalling the
 *    whole `:server:jvmTest` invocation for the runner's lifetime. It is far above any legitimate
 *    test's duration, so it only ever fires on a genuine hang. (Backed by a `timeout-minutes` on the
 *    CI job as the ultimate backstop for a non-interruptible thread deadlock.)
 *  - [extensions] → [FlakyServerSpecRetryExtension] — bounded auto-retry for the known timing-flaky
 *    specs + the heavyweight end-to-end specs.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val timeout: Duration = 120.seconds
    override val extensions: List<Extension> = listOf(FlakyServerSpecRetryExtension)
}

/**
 * Bounded auto-retry for the timing-flaky server specs. Targets the two specs whose CI-only races are
 * diagnosed but not yet root-fixed — `RestoreOrchestratorTest` (DB-pool-drain `SQLITE_BUSY`) and
 * `LibraryFolderSyncAccessTest` (firehose subscribe race) — plus every heavyweight `*E2ETest` /
 * `*EndToEndTest` server spec.
 *
 * **Coverage is not reduced.** Every test still runs and asserts on every PR; only a *failing* leaf
 * (including a per-test [ProjectConfig.timeout] failure) is re-executed, up to [MAX_ATTEMPTS] total —
 * so a genuine regression fails every attempt and stays red, while a transient hiccup gets a clean
 * re-run instead of a false red. Each retry is logged so a flaking test stays visible in CI output.
 *
 * A short [RETRY_DELAY_MS] between attempts lets the previous attempt's async teardown (the
 * `testApplication` shutdown, the port unbind, the connection-pool drain) settle before the retry
 * boots its own server — otherwise the retry can hit the very contention it is recovering from.
 */
private object FlakyServerSpecRetryExtension : TestCaseExtension {
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 750L
    private val FLAKY_SPEC_NAMES = setOf("RestoreOrchestratorTest", "LibraryFolderSyncAccessTest")
    private val FLAKY_SPEC_SUFFIXES = listOf("E2ETest", "EndToEndTest")

    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult,
    ): TestResult {
        if (!isFlakyLeaf(testCase)) return execute(testCase)

        var result = execute(testCase)
        var attempt = 1
        while (attempt < MAX_ATTEMPTS && result.isErrorOrFailure) {
            attempt++
            println(
                "[FLAKY-RETRY] ${testCase.spec::class.simpleName} › ${testCase.name.name} " +
                    "failed transiently; retry $attempt/$MAX_ATTEMPTS",
            )
            delay(RETRY_DELAY_MS)
            result = execute(testCase)
        }
        return result
    }

    private fun isFlakyLeaf(testCase: TestCase): Boolean {
        if (testCase.type != TestType.Test) return false
        val specName = testCase.spec::class.simpleName ?: return false
        return specName in FLAKY_SPEC_NAMES || FLAKY_SPEC_SUFFIXES.any { specName.endsWith(it) }
    }
}
