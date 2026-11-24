package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import java.io.File

/**
 * Single-parse lazy production scopes for `:server` Konsist rules.
 *
 * Calling `Konsist.scopeFromProduction()` or `Konsist.scopeFromProduction("server")` inside each
 * rule re-walks and re-parses Kotlin PSI files on every spec. Under `:server:konsistTest` (one
 * single-JVM fork), parsing once per scope and reusing across rules removes the redundant ~1.6 GB
 * PSI allocations and keeps local worktrees outside `server/src` / `contract/src` out of scope.
 */
private val serverProductionScopeInstance: KoScope by lazy {
    val root = File(Konsist.projectRootPath)
    val dirs = listOf("server/src").filter { File(root, it).isDirectory }
    Konsist.scopeFromDirectories(dirs).slice { "test" !in it.sourceSetName.lowercase() }
}

private val serverAndContractProductionScopeInstance: KoScope by lazy {
    val root = File(Konsist.projectRootPath)
    val dirs = listOf("server/src", "contract/src").filter { File(root, it).isDirectory }
    Konsist.scopeFromDirectories(dirs).slice { "test" !in it.sourceSetName.lowercase() }
}

/** Worktree-free `:server` production scope shared across server Konsist rules. */
internal fun serverProductionScope(): KoScope = serverProductionScopeInstance

/** Worktree-free `:server` + `:contract` production scope for rules that check RPC parity across both modules. */
internal fun serverAndContractProductionScope(): KoScope = serverAndContractProductionScopeInstance
