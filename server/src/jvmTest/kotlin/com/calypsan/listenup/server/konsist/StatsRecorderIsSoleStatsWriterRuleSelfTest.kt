package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize

/**
 * Proves [StatsRecorderIsSoleStatsWriterRule] actually fires, by scoping
 * [com.lemonappdev.konsist.api.Konsist.scopeFromFile] directly at the planted violations in
 * `konsist/fixtures/RogueStatsWriterFixture.kt` (the `UserStatsRepository`/`.upsert(` matcher) and
 * `konsist/fixtures/RogueBookReadsWriterFixture.kt` (the `BookReadsRepository`/`.recordRead(`
 * matcher) — files deliberately excluded from [Konsist.scopeFromProduction] (they live under
 * `jvmTest`), so neither can ever trip the real rule's own production scan, only this self-test's
 * targeted one. Both fixtures exist because the rule combines two independent matchers
 * (`.upsert(` and `.recordRead(`); proving only one fires would leave the other's matcher unproven
 * — a typo there would silently stop flagging real violations while this self-test stayed green.
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

        test("the rule fires when a non-allowlisted class holds BookReadsRepository and calls .recordRead(") {
            val scope =
                Konsist.scopeFromFile(
                    "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueBookReadsWriterFixture.kt",
                )
            StatsRecorderIsSoleStatsWriterRule.findOffenders(scope) shouldHaveSize 1
        }

        test("the raw-table census sees a direct SQLDelight write and one through an alias") {
            val fixture =
                Konsist
                    .scopeFromFile(
                        "server/src/jvmTest/kotlin/com/calypsan/listenup/server/konsist/fixtures/RogueStatsTableWriterFixture.kt",
                    ).files
                    .single()
            StatsRecorderIsSoleStatsWriterRule.directStatsWrites(
                fixture.text,
                StatsRecorderIsSoleStatsWriterRule.statsWriteLabels(),
            ) shouldContainExactlyInAnyOrder listOf("deletePulledForUser", "updateFinishedAtById")
        }

        test("a label is a write unless its statement is a SELECT") {
            val sql =
                """
                -- a comment: notALabel:
                readOne:
                SELECT * FROM t WHERE id = ?;

                touch:
                UPDATE t SET x = 1;
                """.trimIndent()
            StatsRecorderIsSoleStatsWriterRule.writeLabelsIn(sql) shouldContainExactly listOf("touch")
        }
    })
