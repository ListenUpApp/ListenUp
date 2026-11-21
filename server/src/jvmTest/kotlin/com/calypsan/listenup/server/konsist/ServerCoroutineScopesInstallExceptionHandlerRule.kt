package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * The server's twin of the client's `CoroutineScopeInstallsExceptionHandlerRule`: every `CoroutineScope(...)`
 * constructed in `:server` production code installs [com.calypsan.listenup.core.appCoroutineExceptionHandler].
 *
 * **Why.** The shipped server is a Kotlin/Native binary, and on Kotlin/Native an exception that escapes a `launch`
 * on a scope with no `CoroutineExceptionHandler` terminates the process — one stray throw in background work takes
 * every listener's server down. The client rule's scope stops at `/sharedLogic/` and `/contract/`, so it never saw
 * the server.
 *
 * Stricter than the client rule's file-level presence check: each construction's own argument list must name the
 * handler, so a file with two scopes cannot pass on the strength of one. Comments are stripped first, so a KDoc
 * mention of the handler satisfies nothing.
 */
class ServerCoroutineScopesInstallExceptionHandlerRule :
    FunSpec({
        val serverProduction = Regex("/server/src/(commonMain|jvmMain|linuxMain)/")
        val construction = Regex("""\bCoroutineScope\(""")

        test("every server CoroutineScope() installs appCoroutineExceptionHandler (Kotlin/Native crash-net)") {
            val constructions =
                Konsist
                    .scopeFromProduction()
                    .files
                    .filter { serverProduction.containsMatchIn(it.path) }
                    .flatMap { file ->
                        val code = stripComments(file.text)
                        construction.findAll(code).map { match -> file.path to argumentsOf(code, match.range.last) }
                    }

            // Vacuity guard: the server builds four scopes today (Application.kt, AdminUserServiceImpl.kt, two in
            // NativeLogFormatting.kt). A scope-path rename that matched none would otherwise pass green.
            constructions.size shouldBeGreaterThanOrEqual EXPECTED_MIN_CONSTRUCTIONS

            val offenders =
                constructions
                    .filterNot { (_, arguments) -> arguments.contains("appCoroutineExceptionHandler") }
                    .map { (path, arguments) -> "$path: CoroutineScope($arguments)" }

            offenders.shouldBeEmpty()
        }
    })

/** `CoroutineScope(` constructions in `:server` production code when this rule was written. */
private const val EXPECTED_MIN_CONSTRUCTIONS = 4

/** The text between the `(` at [openParen] and its matching `)`, or the rest of [code] if it never closes. */
private fun argumentsOf(
    code: String,
    openParen: Int,
): String {
    var depth = 0
    for (index in openParen until code.length) {
        when (code[index]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return code.substring(openParen + 1, index)
        }
    }
    return code.substring(openParen + 1)
}
