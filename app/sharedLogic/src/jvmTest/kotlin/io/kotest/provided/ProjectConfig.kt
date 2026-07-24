package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.listeners.AfterProjectListener
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeProjectListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.names.DuplicateTestNameMode
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import io.kotest.engine.test.TestResult
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin

/**
 * Kotest project configuration for the JVM test run (auto-discovered by Kotest as
 * `io.kotest.provided.ProjectConfig`).
 *
 * - [extensions]:
 *   - [GlobalKoinIsolationListener] — global-Koin isolation between specs.
 *   - [HeavyweightE2ERetryExtension] — bounded auto-retry for the heavyweight end-to-end specs.
 * - [failOnEmptyTestSuite]: a spec that registers zero tests is almost always a mistake
 *   (a misnamed `test`, a `context` that never adds leaves) — fail instead of passing silently.
 * - [duplicateTestNameMode]: two tests with the same name inside one spec silently shadow each
 *   other's results — make it an error so the copy-paste is caught.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> =
        listOf(GlobalKoinIsolationListener, HeavyweightE2ERetryExtension)
    override val failOnEmptyTestSuite: Boolean = true
    override val duplicateTestNameMode: DuplicateTestNameMode = DuplicateTestNameMode.Error
}

/**
 * Stops the **global** Koin context if one is running. No-op when nothing is started, so repeated
 * calls are harmless. Shared by [GlobalKoinIsolationListener] (spec boundaries) and
 * [HeavyweightE2ERetryExtension] (between retry attempts).
 */
private fun stopGlobalKoinIfRunning() {
    if (GlobalContext.getKoinApplicationOrNull() != null) {
        stopKoin()
    }
}

/**
 * Bounds the **global** Koin context strictly within each spec — clean on entry, clean on exit.
 *
 * **As of the server's switch to `install(KoinIsolated)`, the server no longer touches the global
 * Koin context** — its DI graph is scoped to the `Application` instance, so the worst-offending race
 * (a server spec's late async `stopKoin()` ripping the context out of the next spec → a
 * `NoDefinitionFoundException` for `BookAccessPolicy`, the historical `ImportRpcE2ETest` CI flake) is
 * eliminated at source. This listener stays for the remaining global-Koin starter — client specs that
 * use `KoinTestRule` — and as a belt-and-braces guard so no stale context can leak across specs.
 *
 * Cleaning **before** each spec ([beforeSpec]) makes the suite order-independent. But that alone left
 * a window: a server spec's Koin is torn down **asynchronously** — Ktor's `install(Koin)` registers
 * `on(ApplicationStopped) { stopKoin() }`, and `testApplication`'s shutdown (plus any client
 * `KoinTestRule` teardown) can fire that `stopKoin()` **late**, after the next spec has already
 * installed *its own* context. On a slow/contended CI runner that late `stopKoin()` rips the live
 * context out from under the running spec → an import RPC step resolves into a half-torn-down graph
 * and returns `AppResult.Failure`. Locally (fast box, wide window) it effectively never lands; on the
 * 2-core CI runner it landed intermittently — the classic "passes on my machine" timing flake.
 *
 * Stopping the context **synchronously in [afterSpec]** too closes that window: every spec ends with
 * the global context already gone, so there is no pending async `stopKoin()` left to race the next
 * spec, and `beforeSpec` remains the belt-and-braces guard for a spec that crashed before its
 * `afterSpec` ran. Net: the global-Koin lifecycle is nested inside `[beforeSpec, afterSpec]` with no
 * cross-spec overlap. `stopKoin()` is a no-op when nothing is running, so double-stops are harmless.
 */
private object GlobalKoinIsolationListener :
    BeforeSpecListener,
    AfterSpecListener {
    override suspend fun beforeSpec(spec: Spec) = stopGlobalKoinIfRunning()

    override suspend fun afterSpec(spec: Spec) = stopGlobalKoinIfRunning()
}

/**
 * Bounded auto-retry for the **heavyweight end-to-end specs** — the ones whose class name ends in
 * `E2ETest` or `EndToEndTest`. These boot the full Ktor server (`testApplication { module() }`) or a
 * real client↔server sync engine, then drive a multi-step flow over kotlinx.rpc WebSockets. Their
 * setup touches process-global, asynchronously-torn-down state (the global Koin context, a cold RPC
 * handshake, a freshly-bound port), and on the contended 2-core CI runner that state occasionally
 * isn't settled when the test's first call fires — a transient failure that **never reproduces** on a
 * fast local box. [GlobalKoinIsolationListener] closes the largest such window; this extension is the
 * safety net for the residual timing tail (`ImportRpcE2ETest`, `ContributorUnmergeE2ETest`, …).
 *
 * **Coverage is not reduced.** Every test still runs and asserts on every PR. Only a *failing*
 * leaf test in an E2E spec is re-executed, up to [MAX_ATTEMPTS] total — so a genuine regression fails
 * all attempts and stays red, while a transient hiccup gets a clean re-run instead of a false red.
 * Each retry is logged so a flaking test stays visible in CI output rather than being silently masked.
 *
 * Between attempts the global Koin context is stopped and a short [RETRY_DELAY_MS] elapses, giving any
 * late async `stopKoin()` from the failed attempt time to drain — otherwise the retry's
 * `install(Koin)` could hit "Koin already started" and fail deterministically.
 *
 * **The retry is visible, never silent.** Every retry attempt is appended to `build/e2e-retries.log`
 * (a per-run ledger, truncated in [beforeProject]), which CI cats into the `test-jvm` job summary and
 * uploads with the test-results artifact — so a flake that the retry absorbs still leaves a durable,
 * greppable trace instead of a `println` buried in a green run's log.
 *
 * **The budget turns a worsening flake red.** [afterProject] fails the run if any spec burned retries
 * on more than [MAX_RETRIED_TESTS_PER_SPEC] distinct leaf tests — because the retry math is
 * unforgiving: a genuinely 50%-flaky test passes a 3-attempt retry with probability 1 − 0.5³ ≈ 87.5%,
 * so absent a ceiling a slowly-worsening spec stays invisibly green. The budget is a trend-to-failure
 * guard, not instant strictness: one stubbornly-retried test never trips it.
 */
private object HeavyweightE2ERetryExtension :
    TestCaseExtension,
    BeforeProjectListener,
    AfterProjectListener {
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 500L

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
     * Gradle test task's `doFirst`, before any worker forks. `:app:sharedLogic:jvmTest` runs a single
     * non-forked worker, but `:server:jvmTest` recycles its worker every 25 classes, so truncating in
     * [beforeProject] (which fires per-worker) would wipe earlier workers' retries there. Keeping both
     * extensions append-only with Gradle-side truncation makes them byte-identical and drift-proof.
     * CI cats the accumulated ledger into the job summary and uploads it with the test-results artifact.
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
        if (!isHeavyweightE2ELeaf(testCase)) return execute(testCase)

        var result = execute(testCase)
        var attempt = 1
        while (attempt < MAX_ATTEMPTS && result.isErrorOrFailure) {
            attempt++
            recordRetry(testCase, attempt, result)
            println(
                "[E2E-RETRY] ${testCase.spec::class.simpleName} › ${testCase.name.name} " +
                    "failed transiently; retry $attempt/$MAX_ATTEMPTS",
            )
            stopGlobalKoinIfRunning()
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

    private fun isHeavyweightE2ELeaf(testCase: TestCase): Boolean {
        if (testCase.type != TestType.Test) return false
        val specName = testCase.spec::class.simpleName ?: return false
        return retriesForFlakiness(specName)
    }
}

/**
 * Whether a spec by [specName] is covered by the heavyweight-retry net — either a named real-thread
 * flaky spec or a `*E2ETest`/`*EndToEndTest`. Extracted to an internal top-level function so the
 * membership (which the whole [HeavyweightE2ERetryExtension] hinges on) is directly unit-testable
 * without driving the Kotest lifecycle.
 */
internal fun retriesForFlakiness(specName: String): Boolean =
    specName in RETRY_COVERED_NAMES || RETRY_COVERED_SUFFIXES.any { specName.endsWith(it) }

internal val RETRY_COVERED_NAMES =
    setOf(
        "SyncEngineStateObserversTest",
        "PendingQueueDrainSchedulingTest",
        "ReconcileOnDrainTest",
        "SyncEngineLifecycleTest",
    )

internal val RETRY_COVERED_SUFFIXES = listOf("E2ETest", "EndToEndTest")
