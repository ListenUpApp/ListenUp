package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.names.DuplicateTestNameMode

/**
 * Kotest project configuration for the **`:app:sharedUI` `desktopTest`** run (auto-discovered by
 * Kotest as `io.kotest.provided.ProjectConfig` on this desktopTest test classpath — `ProjectConfig`
 * is discovered per test *classpath*, so `:app:sharedUI:testAndroidHostTest` and every other
 * module's test lane each carry their own copy rather than sharing this one).
 *
 * - [failOnEmptyTestSuite]: a spec that registers zero tests is almost always a mistake (a
 *   misnamed `test`, a `context` that never adds leaves) — fail instead of passing silently.
 * - [duplicateTestNameMode]: two tests with the same name inside one spec silently shadow each
 *   other's results — make it an error so the copy-paste is caught.
 *
 * The discovered-count floor for this lane lives in Gradle (`app/sharedUI/build.gradle.kts`), not
 * here: these two flags catch a spec going wrong, while the floor catches the whole source set
 * going missing. Both matter — `desktopTest` runs in CI's `test-jvm` job and in `verifyLocal`.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val failOnEmptyTestSuite: Boolean = true
    override val duplicateTestNameMode: DuplicateTestNameMode = DuplicateTestNameMode.Error
}
