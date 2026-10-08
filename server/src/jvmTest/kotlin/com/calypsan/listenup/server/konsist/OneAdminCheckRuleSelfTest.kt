package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

/**
 * Proves [OneAdminCheckRule] fires on every spelling it claims to catch: pointed at a fixture with four
 * private admin checks, it names exactly those four lines — and not the one that calls `isAdmin()`.
 */
class OneAdminCheckRuleSelfTest :
    FunSpec({
        test("the rule names the ROOT-first, ADMIN-first, set and when-branch admin checks") {
            val files =
                Konsist
                    .scopeFromFile(
                        "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueAdminChecksFixture.kt",
                    ).files
            OneAdminCheckRule.findOffenders(files).map { it.substringAfterLast(":").toInt() } shouldContainExactly
                listOf(16, 18, 20, 24)
        }
    })
