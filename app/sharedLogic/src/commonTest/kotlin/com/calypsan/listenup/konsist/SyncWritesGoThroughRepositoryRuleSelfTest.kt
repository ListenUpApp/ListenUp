package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Self-test for [SyncWritesGoThroughRepositoryRule]'s matcher, against planted samples.
 *
 * **Why it looks like this.** Its predecessor asserted that the rule's operator constant equalled a
 * hand-copied duplicate of itself — a tautology that could not fail for any reason a reader would
 * care about, and that told you nothing about whether the matcher actually fires. Meanwhile the
 * rule it "covered" had been matching zero declarations since the Exposed→SQLDelight migration.
 * A rule guarding a silent-corruption invariant earns a test that proves detection, so this plants
 * violations and demands they are caught, and plants safe code and demands it is not.
 */
class SyncWritesGoThroughRepositoryRuleSelfTest :
    FunSpec({

        fun detect(source: String): String? =
            SyncWritesGoThroughRepositoryRule.WRITE_CALL
                .find(source)
                ?.groupValues
                ?.get(1)

        test("catches every write verb that bypasses the revision bump") {
            val writes =
                listOf(
                    "db.shelvesQueries.insert(row)",
                    "db.shelvesQueries.update(name = x, id = y)",
                    "db.shelvesQueries.upsert(row)",
                    "sql.genresQueries.updateParentId(parent_id = p, id = i)",
                    "db.tagsQueries.deleteById(id)",
                    "db.booksQueries.softDeleteById(id)",
                    "db.shelfBooksQueries.deleteAllForShelf(shelfId)",
                    "db.moodsQueries.insertOrReplace(row)",
                )
            val undetected = writes.filter { detect(it) == null }
            undetected shouldBe emptyList()
        }

        test("does not fire on reads") {
            val reads =
                listOf(
                    "db.shelvesQueries.selectById(id).executeAsOne()",
                    "db.tagsQueries.existsById(id)",
                    "db.booksQueries.selectIdsAboveRevision(cursor)",
                    "db.genresQueries.descendantIds(prefix).executeAsList()",
                )
            val falsePositives = reads.filter { detect(it) != null }
            falsePositives shouldBe emptyList()
        }

        test("captures the queries wrapper name, which is what scopes the rule to syncable roots") {
            // The rule only reports a hit when this capture is a syncable repository's root
            // queries wrapper — so a write to a non-syncable table (users, sessions) is allowed.
            detect("db.shelvesQueries.insert(row)") shouldBe "shelves"
            detect("sql.userSettingsQueries.update(x)") shouldBe "userSettings"
        }
    })
