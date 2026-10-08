package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

/**
 * Proves [MutatingRpcsAreGatedRule] fires: pointed at a fixture with three planted violations, it names
 * exactly those three — no more (the read, the gated write and the reasoned annotation pass), no fewer.
 */
class MutatingRpcsAreGatedRuleSelfTest :
    FunSpec({
        test("the rule names the ungated write, the comment-only gate and the blank-reason annotation") {
            val scope =
                Konsist.scopeFromFile(
                    "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueCatalogueServiceImplFixture.kt",
                )
            MutatingRpcsAreGatedRule.findOffenders(scope).map { it.substringBefore(" @") } shouldContainExactlyInAnyOrder
                listOf(
                    "RogueCatalogueServiceImpl.deleteThing",
                    "RogueCatalogueServiceImpl.clearThing",
                    "RogueCatalogueServiceImpl.mergeThings",
                )
        }
    })
