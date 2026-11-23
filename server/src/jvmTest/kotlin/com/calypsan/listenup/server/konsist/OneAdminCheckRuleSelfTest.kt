package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

/**
 * Proves [OneAdminCheckRule] fires on every spelling it claims to catch: pointed at a fixture with six
 * private admin checks, it names exactly those six lines — and not the one that calls `isAdmin()` — and
 * its SQL scan names a role literal while ignoring one in a comment.
 */
class OneAdminCheckRuleSelfTest :
    FunSpec({
        test("the rule names the ROOT-first, ADMIN-first, set, when-branch, negated and wrapped admin checks") {
            val files =
                Konsist
                    .scopeFromFile(
                        "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueAdminChecksFixture.kt",
                    ).files
            OneAdminCheckRule.findOffenders(files).map { it.lineNumber } shouldContainExactly
                listOf(17, 19, 21, 25, 29, 32)
        }

        test("the SQL scan names a role literal and ignores one in a comment") {
            val sql =
                """
                -- role IN ('ROOT', 'ADMIN') is the admin set
                listAdmins:
                SELECT * FROM users WHERE role IN ('ROOT', 'ADMIN');
                """.trimIndent()
            OneAdminCheckRule.findSqlOffenders("Rogue.sq", sql).map { it.lineNumber } shouldContainExactly listOf(3)
            OneAdminCheckRule.findSqlOffenders("Clean.sq", "SELECT * FROM users;").shouldBeEmpty()
        }
    })
