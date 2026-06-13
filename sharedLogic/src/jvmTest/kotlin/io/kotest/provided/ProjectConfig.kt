package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
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
 * Guarantees every spec starts with a clean **global** Koin context.
 *
 * Two kinds of spec start the global Koin: server end-to-end specs boot Ktor's `install(Koin)` on
 * the global context (via `testApplication { module() }`), and some client specs start it through
 * `KoinTestRule`. If any spec leaves it running — a crash mid-spec, an ordering gap, a teardown that
 * doesn't fire — the next server boot **reuses that stale context** and can't resolve server-only
 * definitions, surfacing as a `NoDefinitionFoundException` (e.g. for `BookAccessPolicy`) or a hung
 * request. That made `ImportRpcE2ETest` flake on CI: the failure depended purely on which spec ran
 * before it.
 *
 * Stopping any leaked context **before** each spec makes the suite order-independent — every spec,
 * server or client, begins from a clean slate and is then free to start its own Koin.
 */
private object GlobalKoinIsolationListener : BeforeSpecListener {
    override suspend fun beforeSpec(spec: Spec) {
        if (GlobalContext.getKoinApplicationOrNull() != null) {
            stopKoin()
        }
    }
}
