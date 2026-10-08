package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

/**
 * Proves [MutatingRpcsAreGatedRule] fires: pointed at a fixture with seven planted violations, it names
 * exactly those seven — no more (the read, the gated write, the reasoned annotation and the admin denial
 * pass), no fewer.
 */
class MutatingRpcsAreGatedRuleSelfTest :
    FunSpec({
        test("the rule names every planted ungated write, and nothing that is gated or a true read") {
            val scope =
                Konsist.scopeFromFile(
                    "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueCatalogueServiceImplFixture.kt",
                )
            MutatingRpcsAreGatedRule.findOffenders(scope).map { it.substringBefore(" @") } shouldContainExactlyInAnyOrder
                listOf(
                    "RogueCatalogueServiceImpl.deleteThing",
                    "RogueCatalogueServiceImpl.clearThing",
                    "RogueCatalogueServiceImpl.mergeThings",
                    "RogueCatalogueServiceImpl.listenToThing",
                    "RogueCatalogueServiceImpl.getOrCreateThing",
                    "RogueCatalogueServiceImpl.findAndAnnounceThing",
                    "RogueCatalogueServiceImpl.archiveThing",
                )
        }
    })
