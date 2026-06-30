package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize

/**
 * Proves [StatsRecorderIsSoleStatsWriterRule] actually fires, by scoping
 * [com.lemonappdev.konsist.api.Konsist.scopeFromFile] directly at the planted violation in
 * `konsist/fixtures/RogueStatsWriterFixture.kt` — a file deliberately excluded from
 * [Konsist.scopeFromProduction] (it lives under `jvmTest`), so it can never trip the real rule's
 * own production scan, only this self-test's targeted one.
 */
class StatsRecorderIsSoleStatsWriterRuleSelfTest :
    FunSpec({
        test("the rule fires when a non-allowlisted class holds UserStatsRepository and calls .upsert(") {
            val scope =
                Konsist.scopeFromFile(
                    "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueStatsWriterFixture.kt",
                )
            StatsRecorderIsSoleStatsWriterRule.findOffenders(scope) shouldHaveSize 1
        }
    })
