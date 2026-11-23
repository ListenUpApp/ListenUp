package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Proves [MutatingRoutesAreGatedRule] fires: pointed at a fixture with six planted violations, it names
 * exactly those six — no more (the helper gate, the denying admin check, the scoped service, the
 * `checkCanEdit` upload and the reasoned marker pass), no fewer.
 */
class MutatingRoutesAreGatedRuleSelfTest :
    FunSpec({
        test("the rule names the ungated, comment-gated, blank-reason, bare-isAdmin, scoped-upload and wrapped routes") {
            val files =
                Konsist
                    .scopeFromFile(
                        "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueRoutesFixture.kt",
                    ).files
            files.sumOf { MutatingRoutesAreGatedRule.handlersIn(it.text).size } shouldBe 11
            MutatingRoutesAreGatedRule.findOffenders(files).map { it.substringBefore(" @") } shouldContainExactlyInAnyOrder
                listOf(
                    """post("/rogue/ungated")""",
                    """delete("/rogue/comment-gate")""",
                    """put("/rogue/blank-reason")""",
                    """post("/rogue/bare-is-admin")""",
                    """put("/rogue/scoped-upload")""",
                    """post("/rogue/wrapped")""",
                )
        }
    })
