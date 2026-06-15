package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.names.DuplicateTestNameMode
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import io.kotest.engine.test.TestResult
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
 * Two kinds of spec start the global Koin: server end-to-end specs boot Ktor's `install(Koin)` on
 * the global context (via `testApplication { module() }`), and some client specs start it through
 * `KoinTestRule`. If a stale context survives into the next spec, the next server boot **reuses that
 * stale context** and can't resolve server-only definitions, surfacing as a `NoDefinitionFoundException`
 * (e.g. for `BookAccessPolicy`) or a hung request — which made `ImportRpcE2ETest` flake on CI.
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
 */
private object HeavyweightE2ERetryExtension : TestCaseExtension {
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 500L
    private val E2E_SPEC_SUFFIXES = listOf("E2ETest", "EndToEndTest")

    override suspend fun intercept(
        testCase: TestCase,
        execute: suspend (TestCase) -> TestResult,
    ): TestResult {
        if (!isHeavyweightE2ELeaf(testCase)) return execute(testCase)

        var result = execute(testCase)
        var attempt = 1
        while (attempt < MAX_ATTEMPTS && result.isErrorOrFailure) {
            attempt++
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

    private fun isHeavyweightE2ELeaf(testCase: TestCase): Boolean {
        if (testCase.type != TestType.Test) return false
        val specName = testCase.spec::class.simpleName ?: return false
        return E2E_SPEC_SUFFIXES.any { specName.endsWith(it) }
    }
}
