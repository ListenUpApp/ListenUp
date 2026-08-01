package com.calypsan.listenup.gradle

import org.gradle.api.GradleException
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.kotlin.dsl.KotlinClosure2

/**
 * Registers a "did this lane actually run?" guard on [this] test task: if the aggregated
 * task-level suite (`desc.parent == null`) reports fewer than [floor] tests on an UNFILTERED run,
 * the build fails outright rather than reporting a green, collapsed run.
 *
 * Written against `AbstractTestTask` (not the JVM-only `Test`) so ONE helper covers every lane
 * that carries this floor — the JVM lanes (`:contract:jvmTest`, `:app:sharedLogic:jvmTest`/
 * `testAndroidHostTest`, `:app:sharedUI:testAndroidHostTest`, `:server:jvmTest`, all `Test`
 * subtypes) and the native lane (`:server:linuxX64Test`, a `KotlinNativeTest` — a sibling
 * `AbstractTestTask` subtype, NOT a `Test`). Promoted to `build-logic` from four near-identical
 * script-local copies so the filter-stand-down logic and the failure message live in exactly one
 * place. Raising [floor] is a conscious edit, not a rubber stamp for a red build.
 *
 * The floor catches COLLAPSE (a source set silently dropping off the compilation classpath), not
 * attrition — pick a number well below the current honest discovered count so a legitimate test
 * deletion never trips it.
 *
 * ### Filtered runs
 *
 * The floor stands down automatically when [this] task has an explicit test filter active —
 * `--tests` (both lanes), or the Kotest-native `-Dkotest.filter.specs` / `-Dkotest.filter.tests`
 * (JVM `Test` lanes only) — because a filtered run legitimately discovers fewer tests than the
 * unfiltered floor, and that is not the collapse this guard exists to catch. See
 * [isExplicitTestFilterActive] for the detection details.
 */
fun AbstractTestTask.failBelowDiscoveredTestCount(
    floor: Int,
    taskLabel: String,
) {
    afterSuite(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
            if (desc.parent == null && result.testCount < floor && !isExplicitTestFilterActive()) {
                throw GradleException(
                    "$taskLabel discovered only ${result.testCount} tests, below the floor of " +
                        "$floor. No test filter was detected on this run, so the likely cause is a " +
                        "source set silently dropping out of the compilation rather than a " +
                        "legitimate test deletion — investigate before lowering this floor. (If you " +
                        "intended to run a subset with --tests or -Dkotest.filter.specs/.tests and " +
                        "land here anyway, the filter-detection probe below didn't recognize your " +
                        "filter shape — that's a bug in the probe, not a real collapse.)",
                )
            }
        }),
    )
}

/**
 * True when [this] task has an explicit, intentional test-subset filter active — either Gradle's
 * own `--tests` mechanism or (JVM `Test` tasks only) Kotest's native system-property filters.
 *
 * Gradle subtlety: `--tests` populates `DefaultTestFilter.commandLineIncludePatterns`, which is
 * INTERNAL API. The public `TestFilter.getIncludePatterns()` does NOT include patterns supplied on
 * the command line (only ones set programmatically via the `filter { }` DSL), so checking it alone
 * misses the common `./gradlew :m:test --tests "*Foo*"` case entirely. The safe cast to
 * `DefaultTestFilter` degrades to "not filtered" if that internal shape ever changes across a
 * Gradle upgrade — the conservative direction, since it means the floor still applies rather than
 * silently standing down. This part of the probe applies uniformly to both `Test` and
 * `KotlinNativeTest` — the Kotlin/Native Gradle plugin forwards the very same
 * `commandLineIncludePatterns` into the compiled test binary as `--ktest_gradle_filter`, so
 * `--tests` genuinely filters the native lane too.
 *
 * `:server:jvmTest` runs Kotest specs through the Kotest JUnit5 engine, which does its own
 * filtering via the `kotest.filter.specs` / `kotest.filter.tests` system properties
 * (`io.kotest.engine.config.KotestEngineProperties`) — invisible to Gradle's `TestFilter`
 * entirely, since as far as Gradle is concerned every discovered test still "ran" (Kotest excludes
 * them inside the forked JVM). Each JVM lane's `build.gradle.kts` forwards those two system
 * properties into the forked test JVM (see [forwardKotestFilterProperties]) precisely so this
 * probe can see them. There is no native equivalent: the Kotest-generated K/N test entry point
 * runs in-process (no forked JVM to hand a `-D` system property to), so this half of the probe is
 * skipped for non-`Test` tasks — the `--tests` / `commandLineIncludePatterns` check above is the
 * native lane's only filter path.
 */
private fun AbstractTestTask.isExplicitTestFilterActive(): Boolean {
    val commandLineFiltered =
        (filter as? DefaultTestFilter)
            ?.commandLineIncludePatterns
            ?.isNotEmpty() == true
    val kotestFiltered =
        (this as? Test)?.let { jvmTest ->
            listOf("kotest.filter.specs", "kotest.filter.tests").any { key ->
                !jvmTest.systemProperties[key].toString().let { it.isBlank() || it == "null" }
            }
        } == true
    return filter.includePatterns.isNotEmpty() || commandLineFiltered || kotestFiltered
}

/**
 * Forwards Kotest's native `kotest.filter.specs` / `kotest.filter.tests` system properties from
 * the Gradle invocation (`-Dkotest.filter.specs=...`) into [this] task's forked test JVM.
 *
 * Gradle `Test` tasks do not inherit the daemon's system properties by default, so without this a
 * Kotest-run lane like `:server:jvmTest` never sees these properties at all. Forwarding them is
 * future-proofing plus floor-detection, NOT the recommended single-spec recipe: as of Kotest 6.2.3
 * `kotest.filter.specs` is dead code (declared in `KotestEngineProperties`, read by no registered
 * extension) and `kotest.filter.tests` only disables leaf tests after every spec has been
 * instantiated. The working recipe for a fast single-spec loop is `--tests` with the exact
 * fully-qualified class name — see CLAUDE.md "Running a single test". Call this alongside
 * [failBelowDiscoveredTestCount] on any `Test` task whose specs run through Kotest so that a
 * property-filtered run (should a future Kotest wire it up) still stands the floor down. Not
 * applicable to `KotlinNativeTest` — see [isExplicitTestFilterActive]'s KDoc.
 */
fun Test.forwardKotestFilterProperties() {
    listOf("kotest.filter.specs", "kotest.filter.tests").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
