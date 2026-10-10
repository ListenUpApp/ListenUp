package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * Every read RPC either looks at its caller or says in writing why it answers everyone the same.
 *
 * [MutatingRpcsAreGatedRule] exempts reads by name, so a read that returns another user's data, or
 * the whole library past the per-book access rules, is invisible to the build. This rule is its
 * complement: the same population of `override` functions on `*ServiceImpl` classes, but the reads
 * (a [MutatingRpcsAreGatedRule.isReadName] name with no [MutatingRpcsAreGatedRule.WRITE_MARKERS] hit).
 *
 * **A read passes** if its body, comments stripped, (1) mentions a principal (`principal`, `principalProvider`, `callerPrincipal` ...); (2) calls one of
 * [MutatingRpcsAreGatedRule.GATE_CALLS]; (3) calls a function of the same class whose own body
 * mentions `principal` (so a read can delegate its access filter to a private helper); (4) carries
 * `@OpenRead(reason = "…")` with a non-blank reason; or (5) is listed in [UNREVIEWED_OPEN_READS].
 *
 * **[UNREVIEWED_OPEN_READS] is a backlog, not an exemption.** Each entry answers every member
 * identically and has not yet had an access decision. Shrink it; never grow it without a reviewer.
 *
 * **Known limit.** "Mentions `principal`" proves the caller is looked at, not that the answer is
 * filtered correctly; the rule keeps the question from being forgotten, it does not answer it.
 */
class ReadRpcsNameTheirCallerRule :
    FunSpec({
        test("every read ServiceImpl override names its caller, is @OpenRead with a reason, or is on the backlog") {
            val scope = Konsist.scopeFromProduction("server")
            val reads = readOverrides(scope)
            // Vacuity guard: the scope reached server production and found its reads.
            reads.size shouldBeGreaterThanOrEqual 60
            val offenders = findOffenders(scope)
            withClue("Reads that never look at their caller:\n" + offenders.joinToString("\n")) {
                offenders.shouldBeEmpty()
            }
        }
    }) {
    companion object {
        /**
         * `Class.method` reads that answer every member identically and still await an access decision.
         * See the class KDoc: this is a backlog.
         */
        val UNREVIEWED_OPEN_READS: Set<String> =
            setOf(
                "ContributorServiceImpl.getContributor", // one contributor row, whichever books they wrote
                "SeriesServiceImpl.getSeries", // one series row, whichever books it holds
                "GenreServiceImpl.getGenreStats", // book count and total duration across all books, not the caller's
                "MoodServiceImpl.getMoodStats", // book count and total duration across all books, not the caller's
                "TagServiceImpl.getTagStats", // book count and total duration across all books, not the caller's
                "GenreServiceImpl.listUnmappedStrings", // raw genre strings from scanned books, with book counts
                "LibraryAdminServiceImpl.getSetupStatus", // whether the library has folders and whether a scan runs
            )

        private val PRINCIPAL_WORD = Regex("""principal""", RegexOption.IGNORE_CASE)

        private val REASONED_ESCAPE = Regex("""@OpenRead\s*\(\s*(reason\s*=\s*)?"[^"]*\S[^"]*"\s*,?\s*\)""")

        private const val READ_SUFFIX = "ServiceImpl"

        /** A read-named function that does not also write: the exact complement of what the mutating rule checks. */
        private fun isPlainRead(fn: KoFunctionDeclaration): Boolean {
            val body = stripComments(fn.text)
            return MutatingRpcsAreGatedRule.isReadName(fn.name) &&
                MutatingRpcsAreGatedRule.WRITE_MARKERS.none { marker -> marker.containsMatchIn(body) }
        }

        private fun readOverrides(scope: KoScope) =
            scope
                .classes()
                .filter { it.name.endsWith(READ_SUFFIX) }
                .flatMap { cls ->
                    cls
                        .functions()
                        .filter { it.hasOverrideModifier }
                        .filter { fn -> isPlainRead(fn) }
                        .map { fn -> cls to fn }
                }

        /** `Class.method @ path` for every read that never names its caller and carries no reasoned escape. */
        fun findOffenders(scope: KoScope): List<String> =
            scope
                .classes()
                .filter { it.name.endsWith(READ_SUFFIX) }
                .flatMap { cls ->
                    val helpersThatNameTheCaller =
                        cls
                            .functions()
                            .filter { PRINCIPAL_WORD.containsMatchIn(stripComments(it.text)) }
                            .map { it.name }
                            .toSet()
                    cls
                        .functions()
                        .asSequence()
                        .filter { it.hasOverrideModifier }
                        .filter { fn -> isPlainRead(fn) }
                        .filterNot { fn ->
                            "${cls.name}.${fn.name}" in
                                UNREVIEWED_OPEN_READS
                        }.filterNot { fn -> REASONED_ESCAPE.containsMatchIn(stripComments(fn.text)) }
                        .filterNot { fn ->
                            val body = stripComments(fn.text)
                            PRINCIPAL_WORD.containsMatchIn(body) ||
                                MutatingRpcsAreGatedRule.GATE_CALLS.any { it in body } ||
                                helpersThatNameTheCaller.any { helper -> helper != fn.name && "$helper(" in body }
                        }.map { fn -> "${cls.name}.${fn.name} @ ${cls.path}" }
                        .toList()
                }
    }
}
