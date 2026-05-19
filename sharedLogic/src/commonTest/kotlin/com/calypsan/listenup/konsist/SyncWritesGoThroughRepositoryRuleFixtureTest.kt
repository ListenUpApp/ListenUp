package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SyncWritesGoThroughRepositoryRuleFixtureTest :
    FunSpec({
        test("the rule's write-operator set covers all Exposed write APIs that bypass DSL-level interception") {
            // The constant must include every Exposed write operator that touches a SyncableTable
            // outside the repository layer. Missing operators are silent-corruption risk.
            val expected =
                setOf(
                    ".upsert(",
                    ".update(",
                    ".insert(",
                    ".deleteWhere(",
                    ".batchInsert(",
                    ".replace(",
                    ".insertIgnore(",
                    ".deleteAll(",
                    ".deleteIgnoreWhere(",
                    ".batchReplace(",
                    ".upsertReturning(",
                    ".insertAndGetId(",
                    ".replaceFromQuery(",
                    ".updateReturning(",
                )
            SyncWritesGoThroughRepositoryRule.WRITE_OPERATORS shouldBe expected
        }
    })
