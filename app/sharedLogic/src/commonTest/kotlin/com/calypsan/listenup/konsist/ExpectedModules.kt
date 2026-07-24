package com.calypsan.listenup.konsist

/**
 * Every Gradle module whose `src/` holds production Kotlin the architectural rules police.
 *
 * This is the single source of truth for module scope. Two consumers read it: [productionScope]
 * (via the exact-match assertion in `KonsistScopeTest`) and `ArchitectureTest`, which scopes
 * Konsist directly from it.
 *
 * **Adding or moving a module is a deliberate act.** `KonsistScopeTest` asserts that filesystem
 * discovery returns exactly this list, so a module that appears, disappears, or relocates fails
 * that test with a diff naming it. That is the point: the previous hand-maintained list could
 * silently omit a module, and a rule suite that runs green over less code than you think is
 * worse than one that fails.
 *
 * `build-logic` is deliberately absent. It is a separate included build with its own test
 * suites (`:build-logic:convention:test`, `:build-logic:detekt-rules:test`), and its Gradle
 * convention plugins are not the kind of code these client/server rules describe.
 */
internal val EXPECTED_MODULE_DIRS: List<String> =
    listOf(
        "app/androidApp",
        "app/baselineprofile",
        "app/desktopApp",
        "app/sharedLogic",
        "app/sharedUI",
        "contract",
        "server",
        "tools/rpc-guard-ksp",
    )

/** [EXPECTED_MODULE_DIRS] as `src/` paths — the exact shape `discoverModuleSrcDirs` returns. */
internal val EXPECTED_MODULE_SRC_DIRS: List<String> = EXPECTED_MODULE_DIRS.map { "$it/src" }
