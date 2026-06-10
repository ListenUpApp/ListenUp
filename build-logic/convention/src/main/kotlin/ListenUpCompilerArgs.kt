/**
 * Project-wide Kotlin compiler args, applied by every ListenUp convention plugin.
 *
 * - `-Xexpect-actual-classes`  — silence the expect/actual-classes beta warning (KMP).
 * - `-Xreturn-value-checker=check` — opt into the unused-return-value checker.
 * - `-Xexplicit-backing-fields` — enable the explicit-backing-fields language feature.
 */
val LISTENUP_FREE_COMPILER_ARGS: List<String> =
    listOf(
        "-Xexpect-actual-classes",
        "-Xreturn-value-checker=check",
        "-Xexplicit-backing-fields",
    )
