package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.names.DuplicateTestNameMode

/**
 * Kotest project configuration for the **`:contract`** JVM test run (auto-discovered by Kotest as
 * `io.kotest.provided.ProjectConfig` on this module's test classpath — `ProjectConfig` is
 * discovered per test *classpath*, so `:app:sharedLogic:jvmTest` and `:server:jvmTest` each carry
 * their own copy rather than sharing this one).
 *
 * - [failOnEmptyTestSuite]: a spec that registers zero tests is almost always a mistake (a
 *   misnamed `test`, a `context` that never adds leaves) — fail instead of passing silently.
 * - [duplicateTestNameMode]: two tests with the same name inside one spec silently shadow each
 *   other's results — make it an error so the copy-paste is caught.
 *
 * No [extensions] here: the contract round-trip tests are pure serialization checks with no
 * process-global state (Koin, a live server) to isolate between specs.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val failOnEmptyTestSuite: Boolean = true
    override val duplicateTestNameMode: DuplicateTestNameMode = DuplicateTestNameMode.Error
}
