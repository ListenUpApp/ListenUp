/**
 * Project-wide Kotlin compiler args, applied to EVERY compilation (main + test) by every
 * ListenUp convention plugin.
 *
 * - `-Xexpect-actual-classes`  — silence the expect/actual-classes beta warning (KMP).
 * - `-Xreturn-value-checker=check` — opt into the unused-return-value checker; it fires for
 *   declarations in `@MustUseReturnValues` scopes (the `AppResult` repository surface — see
 *   `AppResultSurfaceIsMustUseRule`).
 * (explicit backing fields are STABLE as of Kotlin 2.4.0 — no flag needed.)
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
