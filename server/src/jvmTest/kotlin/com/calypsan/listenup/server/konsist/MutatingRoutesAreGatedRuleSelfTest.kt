package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Proves [MutatingRoutesAreGatedRule] fires: pointed at a fixture with four planted violations, it names
 * exactly those four — no more (the helper gate, the denying admin check, the scoped service and the
 * reasoned marker pass), no fewer.
 */
class MutatingRoutesAreGatedRuleSelfTest :
    FunSpec({
        test("the rule names the ungated route, the comment-only gate, the blank reason and the bare isAdmin") {
            val files =
                Konsist
                    .scopeFromFile(
                        "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueRoutesFixture.kt",
                    ).files
            files.sumOf { MutatingRoutesAreGatedRule.handlersIn(it.text).size } shouldBe 8
            MutatingRoutesAreGatedRule.findOffenders(files).map { it.substringBefore(" @") } shouldContainExactlyInAnyOrder
                listOf(
                    """post("/rogue/ungated")""",
                    """delete("/rogue/comment-gate")""",
                    """put("/rogue/blank-reason")""",
                    """post("/rogue/bare-is-admin")""",
                )
        }
    })
