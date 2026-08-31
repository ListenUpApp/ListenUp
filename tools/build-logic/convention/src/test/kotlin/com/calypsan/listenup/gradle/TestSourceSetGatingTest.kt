package com.calypsan.listenup.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every test source set on disk is run by some gating CI task.
 *
 * [VerifyLocalParityTest] guards the other direction — that what CI runs, `verifyLocal` runs too.
 * Neither it nor anything else noticed that `app/sharedUI/src/desktopTest` was invoked by **no**
 * job at all: eight spec files that were never run and never even compiled on a PR, green by
 * virtue of not existing as far as CI was concerned. A suite that runs nowhere is worse than no
 * suite, because it reads like coverage.
 *
 * The mapping from a source-set directory to the task that runs it is not mechanical
 * (`androidHostTest` is run by `testAndroidHostTest`, `jsTest` by `webKotest`), so it is written
 * out in [RUNNERS] rather than derived. That list is the maintenance cost, and it is deliberate:
 * a source set nobody thought about fails this test by name, with nowhere to put it except a task
 * or an exemption that has to say why.
 */
class TestSourceSetGatingTest {
    private val repoRoot = File(requireNotNull(System.getProperty("listenup.repo.root")))
    private val ciYml = repoRoot.resolve(".github/workflows/ci.yml").readText()

    private companion object {
        /**
         * Source set → the Gradle task that runs it, as ci.yml spells that task.
         *
         * `commonTest` sets have no task of their own: they compile into every target's test
         * compilation, so they run under whichever target tasks are gated. They are listed with
         * the task that carries them, not exempted, because a module whose only target task stops
         * being gated should fail here too.
         */
        val RUNNERS =
            mapOf(
                "app/sharedLogic/src/commonTest" to ":app:sharedLogic:jvmTest",
                "app/sharedLogic/src/jvmTest" to ":app:sharedLogic:jvmTest",
                "app/sharedLogic/src/androidHostTest" to ":app:sharedLogic:testAndroidHostTest",
                "app/sharedUI/src/androidHostTest" to ":app:sharedUI:testAndroidHostTest",
                "app/sharedUI/src/desktopTest" to ":app:sharedUI:desktopTest",
                "app/webApp/src/jsTest" to ":app:webApp:webKotest",
                "contract/src/commonTest" to ":contract:jvmTest",
                "contract/src/jvmTest" to ":contract:jvmTest",
                "server/src/commonTest" to ":server:jvmTest",
                "server/src/jvmTest" to ":server:jvmTest",
                "server/src/linuxX64Test" to ":server:linuxX64Test",
                "tools/rpc-guard-ksp/src/test" to ":tools:rpc-guard-ksp:test",
            )

        /** Where to look. `tools/build-logic` is an included build and is searched separately. */
        val SEARCH_ROOTS = listOf("app", "contract", "server", "tools")

        /**
         * Included-build suites, which ci.yml runs by their own project paths rather than the
         * composite ones the root build uses.
         */
        val INCLUDED_BUILD_RUNNERS =
            mapOf(
                "tools/build-logic/convention/src/test" to ":build-logic:convention:test",
                "tools/build-logic/detekt-rules/src/test" to ":build-logic:detekt-rules:test",
            )
    }

    @Test
    fun `every test source set is run by a gating CI task`() {
        val gated = gatingGradleTasks()
        // Anchors: a parser that silently stopped matching would pass this test vacuously.
        assertTrue(":server:jvmTest" in gated, "ci.yml parser drift — anchor task missing: $gated")
        assertTrue(":app:webApp:webKotest" in gated, "ci.yml parser drift — anchor task missing: $gated")

        val runners = RUNNERS + INCLUDED_BUILD_RUNNERS
        val ungated =
            discoverTestSourceSets().mapNotNull { path ->
                val task = runners[path] ?: return@mapNotNull "$path — not in RUNNERS, so nothing claims to run it"
                if (task in gated) null else "$path — mapped to $task, which no gating ci.yml step runs"
            }

        assertEquals(
            emptyList<String>(),
            ungated,
            "Test source sets that no gating CI job runs. Add the task to ci.yml (and to " +
                "verifyLocal, which VerifyLocalParityTest will demand), or map it in RUNNERS.",
        )
    }

    @Test
    fun `RUNNERS names no source set that has been deleted`() {
        // A stale entry is a quiet lie: it makes the map look like it covers more than it does,
        // and hides the next real gap behind a name nobody recognises any more.
        val onDisk = discoverTestSourceSets().toSet()
        val stale = (RUNNERS + INCLUDED_BUILD_RUNNERS).keys.filterNot { it in onDisk }

        assertEquals(emptyList<String>(), stale, "RUNNERS entries with no directory on disk — delete them.")
    }

    /** Repo-relative paths of every directory under a `src` folder whose name contains `est`. */
    private fun discoverTestSourceSets(): List<String> =
        (SEARCH_ROOTS.map { repoRoot.resolve(it) })
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().maxDepth(SOURCE_SET_DEPTH).toList() }
            .filter { it.isDirectory && it.parentFile?.name == "src" && it.name.contains("est") }
            .filterNot { it.path.contains("/build/") }
            .filter { dir -> dir.walkTopDown().any { it.isFile && it.extension == "kt" } }
            .map { it.relativeTo(repoRoot).path }
            .sorted()

    /** Gradle task tokens from every gating step in ci.yml; skips `continue-on-error: true` steps. */
    private fun gatingGradleTasks(): Set<String> =
        ciYml
            .split(Regex("(?m)^ {6}- name:"))
            .filterNot { "continue-on-error: true" in it }
            .flatMap { step ->
                step
                    .lines()
                    .filter { "./gradlew" in it }
                    .flatMap { line -> line.substringAfter("./gradlew").trim().split(Regex("\\s+")) }
            }.filter { it.isNotBlank() && !it.startsWith("-") }
            .toSet()
}

/** A source set sits four levels below a search root at most, and `tools/build-logic` one deeper. */
private const val SOURCE_SET_DEPTH = 5
