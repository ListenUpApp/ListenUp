package com.calypsan.listenup.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural parity guard between CI's Linux lane and the local pre-push gate.
 *
 * Every gating Gradle task that ci.yml's `lint` and `test-jvm` jobs run must be
 * wired into the root `verifyLocal` task, and the CLAUDE.md "Pushing" table's
 * `Test (JVM)` row must list every gating `test-jvm` task. This drift has shipped
 * twice (fixed in PR #984, reopened by PR #993 adding the compiler-flag canary to
 * CI only) — this test makes the manual "if ci.yml changes, verifyLocal changes"
 * convention self-enforcing. Steps marked `continue-on-error: true` (the Kover
 * coverage report) are non-gating and deliberately excluded.
 *
 * The mapping is a one-way superset check (verifyLocal ⊇ CI): extra local tasks
 * are harmless over-verification; a CI task missing locally is the bug.
 */
class VerifyLocalParityTest {
    private val repoRoot = File(requireNotNull(System.getProperty("listenup.repo.root")))
    private val ciYml = repoRoot.resolve(".github/workflows/ci.yml").readText()
    private val rootBuildScript = repoRoot.resolve("build.gradle.kts").readText()

    @Test
    fun `verifyLocal covers every gating gradle task of CI lint and test-jvm jobs`() {
        val ciTasks = gatingGradleTasks("lint") + gatingGradleTasks("test-jvm")
        // Anchor assertions: if the ci.yml parser rots, fail loudly instead of
        // vacuously passing on an empty set.
        assertTrue(":server:jvmTest" in ciTasks, "ci.yml parser drift — expected anchor task missing: $ciTasks")
        assertTrue("spotlessCheck" in ciTasks, "ci.yml parser drift — expected anchor task missing: $ciTasks")

        val missing = ciTasks - verifyLocalTasks()
        assertEquals(
            emptySet<String>(),
            missing,
            "CI Linux-lane gating tasks missing from verifyLocal's dependsOn in build.gradle.kts. " +
                "Add them (composite-build tasks like :build-logic:* need " +
                "gradle.includedBuild(\"build-logic\").task(...)).",
        )
    }

    @Test
    fun `CLAUDE-md Test JVM row documents every gating test-jvm gradle task`() {
        val row =
            repoRoot
                .resolve("CLAUDE.md")
                .readLines()
                .single { it.startsWith("| `Test (JVM)`") }
        val missing = gatingGradleTasks("test-jvm").filterNot { it in row }
        assertEquals(
            emptyList<String>(),
            missing,
            "test-jvm tasks missing from CLAUDE.md's Pushing-table Test (JVM) row.",
        )
    }

    @Test
    fun `every gating ci-yml job has a Pushing-table row`() {
        // Both are bookkeeping, not gates: dependency-graph submits a graph to GitHub, and
        // dependency-review runs only on pull requests against GitHub's advisory data. Neither has a
        // local equivalent.
        val nonGating = setOf("dependency-graph", "dependency-review")
        val jobNames = topLevelJobKeys().filterNot { it in nonGating }.map { jobName(it) }
        assertTrue("Test (JVM)" in jobNames, "ci.yml parser drift — expected anchor job missing: $jobNames")
        assertTrue("Lint" in jobNames, "ci.yml parser drift — expected anchor job missing: $jobNames")

        val firstCells = pushingTableRows().map { it.split("|")[1] }
        val missing = jobNames.filterNot { name -> firstCells.any { "`$name`" in it } }
        assertEquals(
            emptyList<String>(),
            missing,
            "ci.yml jobs with no row in CLAUDE.md's Pushing table. Add a row, or exclude a job that " +
                "genuinely has no local form (with a comment saying why).",
        )
    }

    @Test
    fun `each documented row lists every gating gradle task of its job`() {
        val rowByJob =
            mapOf(
                "build-server-native" to "Build & Test (server linuxX64)",
                "build-android" to "Build (Android)",
                "test-web-browser" to "Test (web browser)",
                "test-web-browser-server" to "Test (web browser, server-backed)",
            )
        rowByJob.forEach { (job, rowName) ->
            val tasks = gatingGradleTasks(job)
            assertTrue(tasks.isNotEmpty(), "ci.yml parser drift — job '$job' yielded no gradle tasks")
            val row = pushingTableRows().single { it.startsWith("| `$rowName`") }
            assertEquals(
                emptyList<String>(),
                tasks.filterNot { it in row },
                "'$job' gradle tasks missing from CLAUDE.md's Pushing-table '$rowName' row.",
            )
        }
    }

    private fun pushingTableRows(): List<String> =
        repoRoot
            .resolve("CLAUDE.md")
            .readLines()
            .dropWhile { it != "## Pushing" }
            .takeWhile { !it.startsWith("`./gradlew verifyLocal") }
            .filter { it.startsWith("| `") }

    /** Top-level job keys of ci.yml, in file order. */
    private fun topLevelJobKeys(): List<String> =
        ciYml
            .lines()
            .dropWhile { it != "jobs:" }
            .drop(1)
            .filter { it.matches(Regex("^ {2}[a-z][a-z0-9-]*:$")) }
            .map { it.trim().removeSuffix(":") }

    private fun jobName(key: String): String =
        jobBlock(key)
            .lines()
            .first { it.startsWith("    name:") }
            .substringAfter("name:")
            .trim()

    /** Lines of one top-level ci.yml job block (from `  <name>:` to the next 2-space-indented key). */
    private fun jobBlock(name: String): String {
        val lines = ciYml.lines()
        val start = lines.indexOfFirst { it == "  $name:" }
        assertTrue(start >= 0, "job '$name' not found in ci.yml")
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.matches(Regex("^ {2}\\S.*")) }
        return rest.take(if (end >= 0) end else rest.size).joinToString("\n")
    }

    /** Gradle task tokens from a job's gating steps; skips `continue-on-error: true` steps. */
    private fun gatingGradleTasks(job: String): Set<String> =
        jobBlock(job)
            .split(Regex("(?m)^ {6}- name:"))
            .filterNot { "continue-on-error: true" in it }
            .flatMap { step ->
                step
                    .lines()
                    .filter { "./gradlew" in it }
                    .flatMap { line -> line.substringAfter("./gradlew").trim().split(Regex("\\s+")) }
            }.filter { it.isNotBlank() && !it.startsWith("-") }
            .toSet()

    /** Task tokens verifyLocal depends on: quoted dependsOn args plus composite-build task refs. */
    private fun verifyLocalTasks(): Set<String> {
        val start = rootBuildScript.indexOf("tasks.register(\"verifyLocal\")")
        assertTrue(start >= 0, "verifyLocal registration not found in root build.gradle.kts")
        val block = braceBlock(rootBuildScript, start)
        val quoted =
            Regex("dependsOn\\(([^)]*)\\)")
                .findAll(block)
                .flatMap { Regex(""""([^"]+)"""").findAll(it.groupValues[1]) }
                .map { it.groupValues[1] }
        val composite =
            Regex("""includedBuild\("([^"]+)"\)\s*\.task\("([^"]+)"\)""")
                .findAll(block)
                .map { ":${it.groupValues[1]}${it.groupValues[2]}" }
        return (quoted + composite).toSet()
    }

    /** The `{...}` block starting at the first `{` at/after [from], braces balanced. */
    private fun braceBlock(
        text: String,
        from: Int,
    ): String {
        val open = text.indexOf('{', from)
        assertTrue(open >= 0, "no opening brace after index $from")
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> {
                    depth++
                }

                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        error("unbalanced braces after index $from")
    }
}
