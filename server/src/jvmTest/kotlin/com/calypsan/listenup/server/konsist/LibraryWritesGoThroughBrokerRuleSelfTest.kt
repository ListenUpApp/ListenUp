package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly

/**
 * Proves [LibraryWritesGoThroughBrokerRule]'s token predicate fires on a write spelled through the
 * io layer's own helper, by pointing it at the planted `konsist/fixtures/RogueLibraryWriterFixture.kt`
 * — a file outside [Konsist.scopeFromProduction], so it can only trip this targeted check.
 */
class LibraryWritesGoThroughBrokerRuleSelfTest :
    FunSpec({
        test("a write through writeBytesAtomically is a forbidden token") {
            val fixture =
                Konsist
                    .scopeFromFile(
                        "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueLibraryWriterFixture.kt",
                    ).files
                    .single()

            LibraryWritesGoThroughBrokerRule.forbiddenTokensIn(fixture.text) shouldContainExactly
                listOf("writeBytesAtomically(")
        }
    })
