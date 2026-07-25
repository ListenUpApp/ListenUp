package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.names.DuplicateTestNameMode

/**
 * Kotest project configuration for the **`:app:sharedLogic` `testAndroidHostTest`** run
 * (auto-discovered by Kotest as `io.kotest.provided.ProjectConfig` on the androidHostTest test
 * classpath — a *different* classpath from `:app:sharedLogic:jvmTest`'s, which carries its own
 * copy under `src/jvmTest/`. androidHostTest compiles the same `commonTest` specs but is not part
 * of the jvmTest source set tree, so `ProjectConfig` is not inherited between them).
 *
 * - [failOnEmptyTestSuite]: a spec that registers zero tests is almost always a mistake (a
 *   misnamed `test`, a `context` that never adds leaves) — fail instead of passing silently.
 * - [duplicateTestNameMode]: two tests with the same name inside one spec silently shadow each
 *   other's results — make it an error so the copy-paste is caught.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val failOnEmptyTestSuite: Boolean = true
    override val duplicateTestNameMode: DuplicateTestNameMode = DuplicateTestNameMode.Error
}
