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
 * No discovered-count floor is wired for this lane (see `app/sharedUI/build.gradle.kts`) —
 * `desktopTest` is not part of `verifyLocal`/CI's `test-jvm` job, so a collapse here wouldn't be
 * caught by the gate a floor exists to protect anyway.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val failOnEmptyTestSuite: Boolean = true
    override val duplicateTestNameMode: DuplicateTestNameMode = DuplicateTestNameMode.Error
}
