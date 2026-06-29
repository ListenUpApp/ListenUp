package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import java.io.File

/**
 * The project's production Kotlin, scoped to the real Gradle modules only — the canonical scope
 * every architectural rule in this package builds on. Built once and shared across all rules.
 *
 * **Why this exists instead of [Konsist.scopeFromProduction].** That creator walks the whole tree
 * from the project root and parses *every* `.kt` file into a PSI AST before it filters anything.
 * On a developer machine with git worktrees under the repo root (each `.worktrees/<name>/` is a
 * full second copy of the source), it parses tens of thousands of extra files and OOMs the test
 * JVM. CI has no worktrees, so it never hits this — but the local gate becomes unrunnable, which
 * is how a green CI and a red local checkout diverge.
 *
 * Scoping by each top-level module's `src/` directory (one [Konsist.scopeFromDirectories] call)
 * keeps the walk inside the real modules — it never descends into the hidden `.worktrees/`
 * sibling. Dropping test source sets mirrors `scopeFromProduction`'s own rule (source-set name
 * contains `test`). In a clean checkout the result is identical to `scopeFromProduction()`.
 * Modules are discovered dynamically, so a newly added module is covered with no change here.
 *
 * **Built once, by design.** Each `scopeFromDirectories` call re-parses the whole production tree
 * (~1.5k files) into PSI; rebuilding per rule (37+ rules) re-parses that many times over and
 * exhausts the test heap — the empirical reason a per-call variant OOMs even on CI. The scope is
 * immutable, so it is parsed a single time via [lazy] and reused by every rule, matching the
 * single-parse profile that `scopeFromProduction()` had at these call sites.
 */
private val productionScopeInstance: KoScope by lazy {
    val moduleSrcDirs =
        File(Konsist.projectRootPath)
            .listFiles { entry -> entry.isDirectory && !entry.name.startsWith(".") && File(entry, "src").isDirectory }
            .orEmpty()
            .map { "${it.name}/src" }

    Konsist.scopeFromDirectories(moduleSrcDirs).slice { "test" !in it.sourceSetName.lowercase() }
}

/** The worktree-free production scope, shared across all architectural rules. See [productionScopeInstance]. */
fun productionScope(): KoScope = productionScopeInstance
