package com.calypsan.listenup.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.Test

/**
 * Registers a "did this lane actually run?" guard on this project's [laneName] test task: a
 * `<lane>DiscoveryFloor` task finalizes the lane, counts the tests in the JUnit XML the lane wrote, and
 * if an UNFILTERED run counted fewer than [floor] the build fails outright rather than reporting a green,
 * collapsed run.
 *
 * Why a separate task reading the XML: this used to be an `afterSuite` hook on the lane itself, and a lane
 * with no test class never reports a suite — worse, Gradle marks it NO-SOURCE and runs none of its
 * actions at all. Either way the hook never fired, and a zero-test lane passed: exactly the collapse the
 * floor exists for (reproduced 2026-10-08: `:contract:jvmTest`, floor 85, every class excluded, green). A
 * finalizer runs even after a NO-SOURCE lane, and a lane that ran nothing has no XML, which counts as zero.
 *
 * One helper covers every lane that carries a floor — the JVM `Test` lanes (`jvmTest`,
 * `testAndroidHostTest`, `desktopTest`) and the native `KotlinNativeTest` lanes (`linuxX64Test`,
 * `iosSimulatorArm64Test`), which write the same XML to the same `build/test-results/<lane>` directory.
 * Raising [floor] is a conscious edit, not a rubber stamp for a red build.
 *
 * The floor catches COLLAPSE (a source set silently dropping off the compilation classpath), not
 * attrition — pick a number well below the current honest discovered count so a legitimate test
 * deletion never trips it.
 *
 * ### Filtered runs
 *
 * The floor stands down automatically when the lane has an explicit test filter active —
 * `--tests` (both kinds of lane), or the Kotest-native `-Dkotest.filter.specs` / `-Dkotest.filter.tests`
 * (JVM `Test` lanes only) — because a filtered run legitimately discovers fewer tests than the
 * unfiltered floor, and that is not the collapse this guard exists to catch. See
 * [isExplicitTestFilterActive] for the detection details.
 */
fun Project.failBelowDiscoveredTestCount(
    laneName: String,
    floor: Int,
) {
    val lanes = tasks.withType(AbstractTestTask::class.java).matching { it.name == laneName }
    val check =
        tasks.register("${laneName}DiscoveryFloor", TestDiscoveryFloorCheck::class.java) {
            group = "verification"
            description = "Fails when $laneName ran fewer than $floor tests."
            lane.set("${project.path.removeSuffix(":")}:$laneName")
            this.floor.set(floor)
            // Read when the task graph is fixed (the configuration cache stores the answer), after
            // `--tests` has reached the lane's filter.
            filtered.set(project.provider { lanes.any { it.isExplicitTestFilterActive() } })
            reports.set(project.layout.buildDirectory.dir("test-results/$laneName"))
        }
    lanes.configureEach { finalizedBy(check) }
}

/** Fails the build when the lane it finalizes wrote fewer tests to its JUnit XML than its floor. */
abstract class TestDiscoveryFloorCheck : DefaultTask() {
    /** The lane's path, for the failure message. */
    @get:Input
    abstract val lane: Property<String>

    /** The fewest tests an unfiltered run of the lane may count. */
    @get:Input
    abstract val floor: Property<Int>

    /** Whether the run was deliberately narrowed, in which case a low count is expected. */
    @get:Input
    abstract val filtered: Property<Boolean>

    /** The lane's JUnit XML directory. Internal: the check reads it, and must run every time. */
    @get:Internal
    abstract val reports: DirectoryProperty

    /** Counts the reports and fails on a collapse. */
    @TaskAction
    fun check() {
        val xml =
            reports
                .get()
                .asFile
                .listFiles { file -> file.extension == "xml" }
                .orEmpty()
                .map { it.readText() }
        discoveredCountFailure(
            taskLabel = lane.get(),
            floor = floor.get(),
            testCount = countReportedTests(xml),
            isFiltered = filtered.get(),
        )?.let { throw GradleException(it) }
    }
}

private val TESTS_ATTRIBUTE = Regex("""<testsuite\b[^>]*\btests="(\d+)"""")

/** The tests across [reports], each a JUnit XML document: the sum of every `<testsuite tests="…">`. */
internal fun countReportedTests(reports: List<String>): Int =
    reports.sumOf { report ->
        TESTS_ATTRIBUTE
            .find(report)
            ?.groupValues
            ?.get(1)
            ?.toInt() ?: 0
    }

/**
 * The failure message for a collapsed run, or null when the run is acceptable.
 *
 * Kept apart from [TestDiscoveryFloorCheck] so the decision — which is the whole point of the floor — is
 * unit-testable without a live Gradle task.
 *
 * @param isFiltered whether an explicit test filter is active, in which case a lower count is
 *   expected rather than suspicious.
 */
internal fun discoveredCountFailure(
    taskLabel: String,
    floor: Int,
    testCount: Int,
    isFiltered: Boolean,
): String? =
    if (testCount < floor && !isFiltered) {
        "$taskLabel discovered only $testCount tests, below the floor of " +
            "$floor. No test filter was detected on this run, so the likely cause is a " +
            "source set silently dropping out of the compilation rather than a " +
            "legitimate test deletion — investigate before lowering this floor. (If you " +
            "intended to run a subset with --tests or -Dkotest.filter.specs/.tests and " +
            "land here anyway, the filter-detection probe below didn't recognize your " +
            "filter shape — that's a bug in the probe, not a real collapse.)"
    } else {
        null
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
