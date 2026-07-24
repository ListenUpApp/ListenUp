package com.calypsan.listenup.gradle

/**
 * Project-wide Kotlin compiler args — THE single source. Consumed by:
 *  - listenup.kmp.library (contract, sharedLogic, sharedUI): FREE args on every
 *    compilation; MAIN_ONLY args on production compilations.
 *  - listenup.jvm (desktopApp, rpc-guard-ksp): FREE args on every compilation.
 *  - listenup.kmp.server (server): FREE args (+ -Xskip-prerelease-check) on every compilation.
 * Never inline copies of these flags in a module build script.
 *
 * Flags (all experimental -X flags — an unrecognized -X flag is a WARNING, so a
 * Kotlin bump can disarm them silently):
 *  - `-Xexpect-actual-classes` — silence the expect/actual-classes beta warning (KMP).
 *  - `-Xreturn-value-checker=check` — arm the unused-return-value checker for
 *    `@MustUseReturnValues` scopes (the `AppResult` repository surface; see
 *    `AppResultSurfaceIsMustUseRule`).
 *  - `-Xwarning-level=RETURN_VALUE_NOT_USED:error` — promote that diagnostic to a
 *    build error in production code (an ignored `AppResult` is a swallowed error).
 * (explicit backing fields are STABLE as of Kotlin 2.4.0 — no flag needed.)
 *
 * ON EVERY KOTLIN BUMP, re-validate:
 *  1. `./gradlew :build-logic:convention:test` — `ReturnValueGuardCanaryTest` is the
 *     tripwire: it compiles a fixture with these exact constants and fails if the
 *     checker/escalation flags stop producing a compile error.
 *  2. If the canary's version-pin test fails, bump kctfork in lockstep (it is
 *     compiler-locked) before concluding the flags broke.
 *  3. If a flag was renamed/stabilized, update it HERE (one place) and re-run the
 *     canary; if the checker became default-on, the flags may be deletable — the
 *     canary's "no flags" control test tells you.
 */
val LISTENUP_FREE_COMPILER_ARGS: List<String> =
    listOf(
        "-Xexpect-actual-classes",
        "-Xreturn-value-checker=check",
    )

/**
 * Compiler args applied to MAIN (production) compilations only — never test.
 *
 * - `-Xwarning-level=RETURN_VALUE_NOT_USED:error` — an ignored `AppResult` is a swallowed error,
 *   so promote that one diagnostic to a build failure in production code, without flipping global
 *   `warningsAsErrors`. Test code is deliberately left at warning level: Kotest's assertion DSL
 *   (`shouldBe`, `withClue`, `shouldBeInstanceOf`, …) is library-annotated must-use, so elevating
 *   it there would turn ordinary test-helper patterns into build errors. The enforcement that
 *   matters — production must not drop an `AppResult` — lives here.
 */
val LISTENUP_MAIN_ONLY_COMPILER_ARGS: List<String> =
    listOf(
        "-Xwarning-level=RETURN_VALUE_NOT_USED:error",
    )
