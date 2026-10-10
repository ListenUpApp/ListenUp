package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

/**
 * Proves [ReadRpcsNameTheirCallerRule] fires: pointed at a fixture with two planted ungated reads, it
 * names exactly those two — the read of the principal, the read that delegates to a principal-reading
 * helper, and the reasoned `@OpenRead` all pass.
 */
class ReadRpcsNameTheirCallerRuleSelfTest :
    FunSpec({
        test("the rule names every planted read that ignores its caller, and nothing that names it") {
            val scope =
                Konsist.scopeFromFile(
                    "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueReadServiceImplFixture.kt",
                )
            ReadRpcsNameTheirCallerRule.findOffenders(scope).map { it.substringBefore(" @") } shouldContainExactlyInAnyOrder
                listOf(
                    "RogueReadServiceImpl.getEverything",
                    "RogueReadServiceImpl.listAllBooks",
                )
        }
    })
