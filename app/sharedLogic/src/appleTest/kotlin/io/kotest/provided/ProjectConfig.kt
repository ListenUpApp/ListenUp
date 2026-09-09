package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.names.DuplicateTestNameMode

/**
 * Kotest project configuration for the **`:app:sharedLogic` Apple lane** (`iosSimulatorArm64Test`,
 * which runs this module's `commonTest` specs plus `appleTest` — the only lane that compiles
 * `appleMain` at all).
 *
 * `ProjectConfig` is discovered per test *compilation*, so each lane carries its own copy: the
 * jvmTest one under `src/jvmTest/`, the androidHostTest one under `src/androidHostTest/`, and this
 * one. `commonTest` cannot hold a shared copy — `jvmTest` depends on `commonTest`, so the class
 * would collide with the jvmTest copy.
 *
 * - [failOnEmptyTestSuite]: a spec that registers zero tests is almost always a mistake (a misnamed
 *   `test`, a `context` that never adds leaves) — fail instead of passing silently.
 * - [duplicateTestNameMode]: two tests sharing a name inside one spec silently shadow each other's
 *   results; make the copy-paste an error.
 *
 * Deliberately NOT carrying jvmTest's `GlobalKoinIsolationListener` or `HeavyweightE2ERetryExtension`:
 * the retry extension is JVM-only (`java.io.File`, `java.time.Instant`, `ConcurrentHashMap`) and its
 * ledger exists for the contended CI runner's E2E specs, which have no Apple counterpart — the same
 * reasoning the `:server` linuxX64 config records. The discovered-test-count floor for this lane
 * lives in Gradle (`app/sharedLogic/build.gradle.kts`, the `KotlinNativeTest` configuration), not here.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val failOnEmptyTestSuite: Boolean = true
    override val duplicateTestNameMode: DuplicateTestNameMode = DuplicateTestNameMode.Error
}
