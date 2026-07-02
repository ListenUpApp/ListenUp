@file:OptIn(ExperimentalCompilerApi::class)

package com.calypsan.listenup.gradle

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Canary for the AppResult must-use compiler guard.
 *
 * Compiles a fixture with the REAL ListenUpCompilerArgs constants and asserts the
 * RETURN_VALUE_NOT_USED diagnostic fires as an error. If a Kotlin bump renames or
 * drops `-Xreturn-value-checker` / `-Xwarning-level` (unknown -X flags are
 * warnings, not errors — the guard would disarm SILENTLY), this test goes red.
 */
class ReturnValueGuardCanaryTest {
    // A @MustUseReturnValues scope with a deliberately dropped return value —
    // the exact pattern the guard must turn into a build error in production code.
    private val canarySource =
        SourceFile.kotlin(
            "Canary.kt",
            """
            @file:MustUseReturnValues
            package listenup.canary

            fun fallible(): String = "ok"

            fun caller() {
                fallible()
            }
            """.trimIndent(),
        )

    // -Xrender-internal-diagnostic-names makes the compiler print the diagnostic's
    // internal name (RETURN_VALUE_NOT_USED), giving the assertions a stable token.
    private fun compileWith(args: List<String>): JvmCompilationResult =
        KotlinCompilation()
            .apply {
                sources = listOf(canarySource)
                inheritClassPath = true
                verbose = false
                kotlincArguments = args + "-Xrender-internal-diagnostic-names"
            }.compile()

    @Test
    fun `embedded compiler matches the catalog Kotlin version`() {
        val expected = System.getProperty("listenup.expected.kotlin.version")
        assertEquals(expected, KotlinCompilerVersion.VERSION)
    }

    @Test
    fun `guard fires - dropped AppResult-style return is a compile ERROR under the real args`() {
        val result = compileWith(LISTENUP_FREE_COMPILER_ARGS + LISTENUP_MAIN_ONLY_COMPILER_ARGS)
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("RETURN_VALUE_NOT_USED" in result.messages, result.messages)
    }

    @Test
    fun `checker alone only warns - proves the error comes from the escalation arg`() {
        val result = compileWith(LISTENUP_FREE_COMPILER_ARGS)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertTrue("RETURN_VALUE_NOT_USED" in result.messages, result.messages)
    }

    @Test
    fun `no checker flag - the must-use annotation is rejected - proves the checker arg is load-bearing`() {
        // On Kotlin 2.4.0 `@MustUseReturnValues` cannot be used while the return-value
        // checker is disabled: the compiler HARD-ERRORS with
        // IGNORABILITY_ANNOTATIONS_WITH_CHECKER_DISABLED. So `-Xreturn-value-checker=check`
        // is load-bearing — without it the repo's must-use scopes wouldn't even compile,
        // let alone enforce. If a future Kotlin makes the checker default-on, this flips
        // to a non-error and becomes the signal that the flag may be removable.
        val result = compileWith(emptyList())
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("IGNORABILITY_ANNOTATIONS_WITH_CHECKER_DISABLED" in result.messages, result.messages)
    }
}
