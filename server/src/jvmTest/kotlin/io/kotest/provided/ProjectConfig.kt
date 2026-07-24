package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.listeners.AfterProjectListener
import io.kotest.core.listeners.BeforeProjectListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import io.kotest.engine.test.TestResult
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Kotest project configuration for the **`:server`** JVM test run (auto-discovered by Kotest as
 * `io.kotest.provided.ProjectConfig` on the server test classpath — distinct from the `:app:sharedLogic`
 * config, which is not on this module's classpath).
 *
 * The server suite boots an in-process Ktor server + a real SQLite DB. On the contended
 * 2-core CI runner a few timing-sensitive specs intermittently stall or fail in ways that never
 * reproduce on a fast local box — historically the `Test (JVM)` job's recurring hang. Two guards,
 * mirroring the proven `:app:sharedLogic` config:
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
 *
 * **The retry is visible, never silent.** Every retry attempt is appended to `build/e2e-retries.log`
 * (a per-run ledger, truncated in [beforeProject]), which CI cats into the `test-jvm` job summary and
 * uploads with the test-results artifact — so a flake that the retry absorbs still leaves a durable,
 * greppable trace instead of a `println` buried in a green run's log.
 *
 * **The budget turns a worsening flake red.** [afterProject] fails the run if any spec burned retries
 * on more than [MAX_RETRIED_TESTS_PER_SPEC] distinct leaf tests — because the retry math is
 * unforgiving: a genuinely 50%-flaky test passes a 3-attempt retry with probability 1 − 0.5³ ≈ 87.5%,
 * so absent a ceiling a slowly-worsening spec (including the two named [FLAKY_SPEC_NAMES], which are
 * exactly "diagnosed but not root-fixed") stays invisibly green. The budget is a trend-to-failure
 * guard, not instant strictness: one stubbornly-retried test never trips it.
 */
private object FlakyServerSpecRetryExtension :
    TestCaseExtension,
    BeforeProjectListener,
    AfterProjectListener {
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 750L
    private val FLAKY_SPEC_NAMES = setOf("RestoreOrchestratorTest", "LibraryFolderSyncAccessTest")
    private val FLAKY_SPEC_SUFFIXES = listOf("E2ETest", "EndToEndTest")

    /**
     * Trend-to-failure guard, not instant strictness: a spec may burn retries on up to this many
     * DISTINCT leaf tests in one run. Beyond it the run fails — a spec that flaky is no longer a
     * "residual timing tail", it is a worsening defect the retry would otherwise keep absorbing (a
     * 50%-flaky test passes 3 attempts ~87.5% of the time). Raise only with a ledger-backed argument.
     */
    private const val MAX_RETRIED_TESTS_PER_SPEC = 3

    /** Longest error one-liner recorded per ledger entry. */
    private const val MAX_ERROR_CHARS = 300

    /**
     * Machine-readable retry ledger, one line per retry ATTEMPT:
     * `<ISO-8601 UTC>\t<spec>\t<test>\tattempt=<n>/<max>\t<original failure one-liner>`.
     * Location is provided by the Gradle `jvmTest` task via the `listenup.e2eRetryLedger` system
     * property (an absolute path under this module's `build/`), so it is independent of the Test
     * task's `workingDir` — `:server:jvmTest` redirects that dir under `build/test-cwd`, which a
     * bare relative path would follow. The `build/e2e-retries.log` fallback keeps ad-hoc IDE runs
     * working.
     *
     * This extension is strictly **append-only** — the ledger is truncated once per task run by the
     * Gradle test task's `doFirst`, before any worker forks. `:server:jvmTest` recycles its worker
     * JVM every 25 classes (`setForkEvery(25)`), so [beforeProject] fires once per worker; if it
     * truncated, each fresh worker would wipe the previous worker's retries and only the last batch
     * would survive. Truncating in Gradle (pre-fork, once) lets every worker's appends accumulate.
     * Workers run sequentially (no `maxParallelForks`) and each line is a single `O_APPEND` write, so
     * appends from successive workers never interleave or clobber. CI cats the accumulated ledger
     * into the job summary and uploads it with the test-results artifact.
     */
    private val ledgerFile = File(System.getProperty("listenup.e2eRetryLedger") ?: "build/e2e-retries.log")

    private val retriedTestsBySpec = ConcurrentHashMap<String, MutableSet<String>>()

    override suspend fun beforeProject() {
        retriedTestsBySpec.clear()
        ledgerFile.parentFile?.mkdirs()
    }

    override suspend fun afterProject() {
        // Per-worker in-memory accounting is still complete PER SPEC: Gradle assigns a whole test
        // class to one worker, so every leaf of a given spec (and thus its full retry set) runs in the
        // JVM whose afterProject checks it — a spec never straddles workers.
        val overBudget = retriedTestsBySpec.filterValues { it.size > MAX_RETRIED_TESTS_PER_SPEC }
        if (overBudget.isNotEmpty()) {
            val detail =
                overBudget.entries.joinToString("; ") { (spec, tests) ->
                    "$spec retried ${tests.size} distinct tests (budget $MAX_RETRIED_TESTS_PER_SPEC): ${tests.sorted()}"
                }
            throw AssertionError(
                "E2E retry budget exceeded — $detail. " +
                    "The auto-retry is masking a worsening flake; root-cause it (ledger: ${ledgerFile.path}) " +
                    "or raise MAX_RETRIED_TESTS_PER_SPEC with justification.",
            )
        }
    }

    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult,
    ): TestResult {
        if (!isFlakyLeaf(testCase)) return execute(testCase)

        var result = execute(testCase)
        var attempt = 1
        while (attempt < MAX_ATTEMPTS && result.isErrorOrFailure) {
            attempt++
            recordRetry(testCase, attempt, result)
            println(
                "[FLAKY-RETRY] ${testCase.spec::class.simpleName} › ${testCase.name.name} " +
                    "failed transiently; retry $attempt/$MAX_ATTEMPTS",
            )
            delay(RETRY_DELAY_MS)
            result = execute(testCase)
        }
        return result
    }

    /** Appends one ledger line recording the ORIGINAL failure that triggered this retry. */
    private fun recordRetry(
        testCase: TestCase,
        attempt: Int,
        failed: TestResult,
    ) {
        val specName = testCase.spec::class.simpleName ?: "UnknownSpec"
        val testName = testCase.name.name.singleLine()
        val error =
            failed.errorOrNull
                ?.let { "${it::class.simpleName}: ${it.message}" }
                .orEmpty()
                .ifBlank { "unknown error" }
                .singleLine()
                .take(MAX_ERROR_CHARS)
        retriedTestsBySpec.getOrPut(specName) { ConcurrentHashMap.newKeySet() }.add(testName)
        val line =
            listOf(
                Instant.now().toString(),
                specName,
                testName,
                "attempt=$attempt/$MAX_ATTEMPTS",
                error,
            ).joinToString(separator = "\t", postfix = "\n")
        synchronized(ledgerFile) {
            ledgerFile.appendText(line)
        }
    }

    private fun String.singleLine(): String = replace(Regex("""\s+"""), " ").trim()

    private fun isFlakyLeaf(testCase: TestCase): Boolean {
        if (testCase.type != TestType.Test) return false
        val specName = testCase.spec::class.simpleName ?: return false
        return specName in FLAKY_SPEC_NAMES || FLAKY_SPEC_SUFFIXES.any { specName.endsWith(it) }
    }
}
