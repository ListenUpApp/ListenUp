/**
 * Project-wide Kotlin compiler args, applied by every ListenUp convention plugin.
 *
 * - `-Xexpect-actual-classes`  — silence the expect/actual-classes beta warning (KMP).
 * - `-Xreturn-value-checker=check` — opt into the unused-return-value checker.
 * (explicit backing fields are STABLE as of Kotlin 2.4.0 — no flag needed.)
 */
val LISTENUP_FREE_COMPILER_ARGS: List<String> =
    listOf(
        "-Xexpect-actual-classes",
        "-Xreturn-value-checker=check",
    )
