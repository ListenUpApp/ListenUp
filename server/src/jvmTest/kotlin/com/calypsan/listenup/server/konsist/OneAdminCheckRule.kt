package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual

/**
 * The server spells "is this caller an admin?" once, in `auth/Roles.kt`. Main once had more than a dozen
 * private copies; a copy that drifts (say, forgets ROOT) is a privilege bug nobody would see.
 *
 * A private copy is any of the shapes in [SPELLINGS], in either order: `ROOT || … ADMIN`, a set or list
 * of the two (`setOf(ROOT, ADMIN)`), or a `when` branch naming both (`ROOT, ADMIN ->`).
 */
class OneAdminCheckRule :
    FunSpec({
        test("only auth/Roles.kt compares a role against ROOT and ADMIN") {
            val files = Konsist.scopeFromProduction("server").files
            files.size shouldBeGreaterThanOrEqual 300
            val offenders = findOffenders(files.filterNot { it.path.endsWith("/auth/Roles.kt") })
            withClue("Private admin checks:\n" + offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
        }
    }) {
    companion object {
        private const val ROLE = """(?:UserRole(?:Column)?\.)?(?:ROOT|ADMIN)\b"""

        /** Each way a private admin check has been, or could be, spelled. */
        val SPELLINGS =
            listOf(
                Regex("""\b$ROLE\s*\|\|[^\n;]*\b$ROLE"""),
                Regex("""\b(?:setOf|listOf|arrayOf|enumSetOf|hashSetOf)\s*\(\s*$ROLE\s*,\s*$ROLE\s*\)"""),
                Regex("""\b$ROLE\s*,\s*$ROLE\s*->"""),
            )

        /** `path:line` for every private admin check in [files], comments stripped. */
        fun findOffenders(files: List<KoFileDeclaration>): List<String> =
            files.flatMap { file ->
                blankComments(file.text).lines().mapIndexedNotNull { index, line ->
                    if (SPELLINGS.any { it.containsMatchIn(line) }) "${file.path}:${index + 1}" else null
                }
            }
    }
}
