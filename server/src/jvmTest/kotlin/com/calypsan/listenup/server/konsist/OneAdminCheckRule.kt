package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import java.io.File

/**
 * The server spells "is this caller an admin?" once, in `auth/Roles.kt`. Main once had more than a dozen
 * private copies; a copy that drifts (say, forgets ROOT) is a privilege bug nobody would see.
 *
 * A private copy is any of the shapes in [SPELLINGS], in either order and even with the operator wrapped
 * onto the next line: `ROOT || … ADMIN` (or `ROOT && … ADMIN`, the negated form), a set or list of the two
 * (`setOf(ROOT, ADMIN)`), a `when` branch naming both (`ROOT, ADMIN ->`), or a comparison against
 * `MEMBER` (`role != MEMBER` is the same admin check, by negation). The SQL schema is scanned too: a
 * `'ROOT'` / `'ADMIN'` literal in a `.sq` file is the SQL-side spelling of the same check.
 *
 * Every site that legitimately matches is in [REVIEWED] with its reason, and an entry that no longer
 * matches fails, so the list cannot rot into a blanket pass.
 */
class OneAdminCheckRule :
    FunSpec({
        test("only auth/Roles.kt compares a role against ROOT and ADMIN") {
            val files = Konsist.scopeFromProduction("server").files
            files.size shouldBeGreaterThanOrEqual 300
            val offenders =
                findOffenders(files.filterNot { it.path.endsWith("/auth/Roles.kt") })
                    .filterNot { it.isReviewed() }
            withClue("Private admin checks:\n" + offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
        }

        test("only reviewed SQL statements name the ROOT and ADMIN roles") {
            val sqlFiles = sqlFiles()
            sqlFiles.size shouldBeGreaterThanOrEqual 20
            val offenders = sqlFiles.flatMap { findSqlOffenders(it.path, it.readText()) }.filterNot { it.isReviewed() }
            withClue("Role literals in SQL:\n" + offenders.joinToString("\n")) { offenders.shouldBeEmpty() }
        }

        test("every reviewed site still matches, so the list cannot go stale") {
            val kotlinHits = findOffenders(Konsist.scopeFromProduction("server").files)
            val sqlHits = sqlFiles().flatMap { findSqlOffenders(it.path, it.readText()) }
            val stale = REVIEWED.filterNot { reviewed -> (kotlinHits + sqlHits).any { reviewed.matches(it) } }
            withClue("Reviewed sites that no longer match:\n" + stale.joinToString("\n")) { stale.shouldBeEmpty() }
        }
    }) {
    companion object {
        private const val ROLE = """(?:UserRole(?:Column)?\.)?(?:ROOT|ADMIN)\b"""
        private const val ROOT = """(?:UserRole(?:Column)?\.)?ROOT\b"""
        private const val ADMIN = """(?:UserRole(?:Column)?\.)?ADMIN\b"""
        private const val MEMBER = """(?:UserRole(?:Column)?\.)?MEMBER\b"""

        /** Each way a private admin check has been, or could be, spelled. `\s` spans a wrapped operator. */
        val SPELLINGS =
            listOf(
                Regex("""\b$ROLE\s*\|\|\s*[^\n;]*\b$ROLE"""),
                // The negated form, `!= ROOT && != ADMIN`, either order; `ROOT && … ROOT` is a root-only
                // check (only ROOT may mint ROOT), not an admin check, so the two roles must differ.
                Regex("""\b$ROOT\s*&&\s*[^\n;]*\b$ADMIN"""),
                Regex("""\b$ADMIN\s*&&\s*[^\n;]*\b$ROOT"""),
                Regex("""\b(?:setOf|listOf|arrayOf|enumSetOf|hashSetOf)\s*\(\s*$ROLE\s*,\s*$ROLE\s*\)"""),
                Regex("""\b$ROLE\s*,\s*$ROLE\s*->"""),
                // Member-vs-not is the same check: `role != MEMBER` means "is an admin".
                Regex("""(?:!=|==)\s*$MEMBER"""),
                Regex("""\b$MEMBER\s*(?:!=|==)"""),
            )

        /** A role literal in SQL — the schema-side spelling. */
        private val SQL_ROLE_LITERAL = Regex("""'(?:ROOT|ADMIN)'""")

        /** A match that is reviewed and allowed: a path suffix and a fragment of the matched line, with why. */
        data class Reviewed(
            val pathSuffix: String,
            val lineFragment: String,
            val reason: String,
        ) {
            fun matches(hit: Hit): Boolean = hit.path.endsWith(pathSuffix) && lineFragment in hit.line
        }

        /** One match: where, and the line it starts on. */
        data class Hit(
            val path: String,
            val lineNumber: Int,
            val line: String,
        ) {
            override fun toString(): String = "$path:$lineNumber: ${line.trim()}"
        }

        val REVIEWED =
            listOf(
                Reviewed(
                    "/api/DefaultAllBooksGrantIssuer.kt",
                    "if (role != UserRoleColumn.MEMBER) return",
                    "grants the default ALL_BOOKS read by role (admins already see everything); not an access check",
                ),
                Reviewed(
                    "/api/AdminUserServiceImpl.kt",
                    "demoted = user.role == UserRoleColumn.ADMIN && mergedRole == UserRoleColumn.MEMBER",
                    "detects the ADMIN → MEMBER transition of a role edit; not an access check",
                ),
                Reviewed(
                    "/api/AdminUserServiceImpl.kt",
                    "val demotingAdmin = user.role == UserRoleColumn.ADMIN && newRole == UserRole.MEMBER",
                    "detects the ADMIN → MEMBER transition of a role edit; not an access check",
                ),
                Reviewed(
                    "/Users.sq",
                    "CHECK (role IN ('ROOT', 'ADMIN', 'MEMBER'))",
                    "the column's enum constraint, naming every role; not an access check",
                ),
                Reviewed(
                    "/Users.sq",
                    "SELECT COUNT(*) FROM users WHERE deleted_at IS NULL AND role IN ('ROOT', 'ADMIN');",
                    "the SQL-side admin definition (counting admins, for the last-admin guard); Kotlin cannot reach into SQL",
                ),
                Reviewed(
                    "/Users.sq",
                    "WHERE deleted_at IS NULL AND status = 'ACTIVE' AND role IN ('ROOT', 'ADMIN');",
                    "the SQL-side admin definition (listing active admins); Kotlin cannot reach into SQL",
                ),
                Reviewed(
                    "/HardcoverConnections.sq",
                    "ORDER BY CASE u.role WHEN 'ROOT' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END",
                    "ranks connections by role for a stable pick order; not an access check",
                ),
            )

        private fun Hit.isReviewed(): Boolean = REVIEWED.any { it.matches(this) }

        /** Every private admin check in [files], comments blanked, matched across wrapped lines. */
        fun findOffenders(files: List<KoFileDeclaration>): List<Hit> =
            files.flatMap { file ->
                val text = blankComments(file.text)
                val lines = text.lines()
                SPELLINGS
                    .asSequence()
                    .flatMap { spelling -> spelling.findAll(text).map { it.range.first } }
                    .map { offset -> text.substring(0, offset).count { it == '\n' } }
                    .distinct()
                    .sorted()
                    .map { index -> Hit(file.path, index + 1, lines[index]) }
                    .toList()
            }

        /** Every role literal in the SQL [text] of [path], `--` comments stripped. */
        fun findSqlOffenders(
            path: String,
            text: String,
        ): List<Hit> =
            text.lines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("--")
                if (SQL_ROLE_LITERAL.containsMatchIn(code)) Hit(path, index + 1, line) else null
            }

        private fun sqlFiles(): List<File> =
            File(Konsist.projectRootPath, "server/src/commonMain/sqldelight")
                .walkTopDown()
                .filter { it.isFile && it.extension == "sq" }
                .toList()
    }
}
