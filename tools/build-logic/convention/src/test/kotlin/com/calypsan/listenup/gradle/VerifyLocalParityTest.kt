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
                .flatMap { Regex("\"([^\"]+)\"").findAll(it.groupValues[1]) }
                .map { it.groupValues[1] }
        val composite =
            Regex("includedBuild\\(\"([^\"]+)\"\\)\\s*\\.task\\(\"([^\"]+)\"\\)")
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
