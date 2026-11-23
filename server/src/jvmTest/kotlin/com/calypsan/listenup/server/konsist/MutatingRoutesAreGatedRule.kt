package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Every mutating REST handler is gated, or says in writing why it is open to every member.
 *
 * [MutatingRpcsAreGatedRule] covers the RPC surface; the blob routes (covers, images, uploads, backup
 * and import archives) are the other way in, and a `put`/`post`/`delete` that forgets its gate is just as
 * silent there.
 *
 * **Scope.** Every file under a `server/routes/` package in server production, and every handler block
 * opened by `put`, `post`, `delete` or `patch` at the start of a line — the header may wrap across lines.
 *
 * **A handler passes** if its body, comments stripped, contains one of:
 * - a gate helper — `require…Permission(` or `require…Admin(`;
 * - an admin check in a denial shape — `if (!….isAdmin()) return…` (a bare `.isAdmin()` that denies
 *   nothing does not count);
 * - a service-level edit gate — `checkCanEdit(` (`ContributorRoutes.kt`, `SeriesRoutes.kt`), the scoped
 *   service's own permission check, called before the body is read;
 * - a scoped-service call — `scoped(`, which binds the caller to a `ServiceImpl` whose own gate
 *   [MutatingRpcsAreGatedRule] already checks — **except** in a handler that receives a body
 *   (`receive…(`, `storeMultipartImage(`, `streamFirstFilePartTo(`): `scoped(` authenticates but
 *   authorises nothing, and an upload lands bytes on disk before any service method runs, so it needs
 *   one of the real gates above;
 * - or the marker comment `// open-to-all: <reason>` with a non-blank reason.
 *
 * **Known limit.** Like the RPC rule, it trusts helper names: a `require…Admin(` helper is a review of
 * that helper. Block boundaries come from brace counting with comments blanked, so a string literal
 * holding an unbalanced brace would confuse it; none exists today.
 */
class MutatingRoutesAreGatedRule :
    FunSpec({
        test("every mutating REST handler is gated or carries an open-to-all reason") {
            val routeFiles =
                Konsist
                    .scopeFromProduction("server")
                    .files
                    .filter { "/server/routes/" in it.path }
            // Vacuity guards: the scope found the route files, and the handlers inside them.
            routeFiles.size shouldBeGreaterThanOrEqual 10
            routeFiles.sumOf { handlersIn(it.text).size } shouldBeGreaterThanOrEqual 11
            val offenders = findOffenders(routeFiles)
            withClue("Ungated mutating routes:\n" + offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
        }
    }) {
    companion object {
        /**
         * `put`/`post`/`delete`/`patch` at a line start, an optional type argument, an optional argument
         * list (which may wrap across lines and hold one level of nested parentheses), then the block's `{`.
         */
        private val HANDLER_START =
            Regex(
                """^[ \t]*(put|post|delete|patch)\s*(<[^>]*>)?\s*(\((?:[^()]|\([^()]*\))*\))?\s*\{""",
                RegexOption.MULTILINE,
            )

        /** Gates that authorise: each denies a caller who may not perform the mutation. */
        private val AUTHORISING_GATES =
            listOf(
                Regex("""\brequire\w*Permission\("""),
                Regex("""\brequire\w*Admin\("""),
                ADMIN_DENIAL_SHAPE,
                Regex("""\bcheckCanEdit\("""),
            )

        /** Binds the caller to a scoped service whose methods gate themselves — enough only for a body-less handler. */
        private val SCOPED_SERVICE = Regex("""\bscoped\(""")

        /**
         * A handler that reads a request body or multipart upload — the bytes are buffered or written before
         * any scoped service method runs. `receive…(` covers `receive`, `receiveMultipart`,
         * `receiveFramedImage` (`ImageUploadOutcome.kt`) and `receiveOneFile` (`UploadRoutes.kt`).
         */
        private val RECEIVES_BODY = Regex("""\b(receive\w*|storeMultipartImage|streamFirstFilePartTo)\s*[<(]""")

        private val OPEN_TO_ALL = Regex("""//[ \t]*open-to-all:[ \t]*\S""")

        /** One handler: its header as written (`post(UploadRoutePaths.SESSIONS)`), its raw body and its 1-based line. */
        data class Handler(
            val header: String,
            val line: Int,
            val raw: String,
            val code: String,
        )

        /** Every `put`/`post`/`delete`/`patch` handler block in [source]. */
        fun handlersIn(source: String): List<Handler> {
            val blanked = blankComments(source)
            return HANDLER_START
                .findAll(blanked)
                .map { match ->
                    val open = match.range.last
                    val close = matchingBrace(blanked, open)
                    Handler(
                        header =
                            match.value
                                .trim()
                                .removeSuffix("{")
                                .trim()
                                .replace(Regex("""\s+"""), " ")
                                .replace("( ", "(")
                                .replace(", )", ")")
                                .replace(",)", ")"),
                        line = source.substring(0, match.range.first).count { it == '\n' } + 1,
                        raw = source.substring(open, close + 1),
                        code = blanked.substring(open, close + 1),
                    )
                }.toList()
        }

        /** `header @ path:line` for every handler that is neither gated nor marked open-to-all. */
        fun findOffenders(files: List<KoFileDeclaration>): List<String> =
            files.flatMap { file ->
                handlersIn(file.text)
                    .filterNot { handler -> isGated(handler.code) }
                    .filterNot { handler -> OPEN_TO_ALL.containsMatchIn(handler.raw) }
                    .map { "${it.header} @ ${file.path}:${it.line}" }
            }

        /** An authorising gate, or — for a handler that takes no body — a scoped service. */
        private fun isGated(code: String): Boolean =
            AUTHORISING_GATES.any { it.containsMatchIn(code) } ||
                (SCOPED_SERVICE.containsMatchIn(code) && !RECEIVES_BODY.containsMatchIn(code))

        /** The index of the `}` closing the `{` at [open] in [text]. */
        private fun matchingBrace(
            text: String,
            open: Int,
        ): Int {
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
            }
            error("Unbalanced braces after offset $open")
        }
    }
}
