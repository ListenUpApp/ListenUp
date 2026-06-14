package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.names.DuplicateTestNameMode
import io.kotest.core.spec.Spec
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin

/**
 * Kotest project configuration for the JVM test run (auto-discovered by Kotest as
 * `io.kotest.provided.ProjectConfig`).
 *
 * - [extensions]: global-Koin isolation between specs — see [GlobalKoinIsolationListener].
 * - [failOnEmptyTestSuite]: a spec that registers zero tests is almost always a mistake
 *   (a misnamed `test`, a `context` that never adds leaves) — fail instead of passing silently.
 * - [duplicateTestNameMode]: two tests with the same name inside one spec silently shadow each
 *   other's results — make it an error so the copy-paste is caught.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val extensions: List<Extension> = listOf(GlobalKoinIsolationListener)
    override val failOnEmptyTestSuite: Boolean = true
    override val duplicateTestNameMode: DuplicateTestNameMode = DuplicateTestNameMode.Error
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

    private fun stopGlobalKoinIfRunning() {
        if (GlobalContext.getKoinApplicationOrNull() != null) {
            stopKoin()
        }
    }
}
