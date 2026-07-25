package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.names.DuplicateTestNameMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Kotest project configuration for the **`:server` linuxX64** test run — the native peer of the
 * jvmTest config (`server/src/jvmTest/.../io/kotest/provided/ProjectConfig.kt`). Kotest discovers
 * `io.kotest.provided.ProjectConfig` on native through the KSP-generated entry point, the same name
 * it looks for on the JVM; the two never collide because each compiles into its own binary.
 *
 * This lane only started executing Kotest specs when the `io.kotest` Gradle plugin was applied — before
 * that the Kotlin/Native runner collected only `kotlin.test`-annotated specs, so 54 FunSpec specs from
 * `commonTest` sat on the classpath unexecuted while the lane reported green. These settings are the
 * honesty guards that make a repeat of that visible:
 *
 *  - [failOnEmptyTestSuite] — a spec that registers zero tests is almost always a mistake (a misnamed
 *    `test`, a `context` that never adds leaves). Fail instead of passing silently. This is the exact
 *    shape of the bug above, one spec down.
 *  - [duplicateTestNameMode] — two tests sharing a name inside one spec silently shadow each other's
 *    results; make the copy-paste an error.
 *  - [timeout] — the native lane boots real CIO servers and a real SQLite DB. A generous per-test
 *    ceiling turns a genuine hang into a named "timed out" failure instead of stalling the whole
 *    invocation. Far above any legitimate test's duration, so it only fires on a real hang.
 *
 * Deliberately NOT carrying jvmTest's `FlakyServerSpecRetryExtension`: that extension is JVM-only
 * (`java.io.File`, `java.time.Instant`, `ConcurrentHashMap`) and its retry ledger exists for the
 * contended 2-core CI runner's E2E specs, which have no native counterpart.
 *
 * The discovered-test-count floor for this lane lives in Gradle (`server/build.gradle.kts`, the
 * `KotlinNativeTest` configuration), not here — Gradle's test task sees the run's true total.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val timeout: Duration = 120.seconds
    override val failOnEmptyTestSuite: Boolean = true
    override val duplicateTestNameMode: DuplicateTestNameMode = DuplicateTestNameMode.Error
}
