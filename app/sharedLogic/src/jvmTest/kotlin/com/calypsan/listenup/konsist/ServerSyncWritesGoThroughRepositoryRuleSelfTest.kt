package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Self-test for [ServerSyncWritesGoThroughRepositoryRule]'s matcher, against planted samples.
 *
 * **Why it looks like this.** Its predecessor asserted that the rule's operator constant equalled a
 * hand-copied duplicate of itself — a tautology that could not fail for any reason a reader would
 * care about, and that told you nothing about whether the matcher actually fires. Meanwhile the
 * rule it "covered" had been matching zero declarations since the Exposed→SQLDelight migration.
 * A rule guarding a silent-corruption invariant earns a test that proves detection, so this plants
 * violations and demands they are caught, and plants safe code and demands it is not — against the
 * write labels the rule itself reads from the `.sq` files.
 */
class ServerSyncWritesGoThroughRepositoryRuleSelfTest :
    FunSpec({

        val writeLabels = ServerSyncWritesGoThroughRepositoryRule.syncableRootWriteLabels()

        fun detect(source: String): List<String> = ServerSyncWritesGoThroughRepositoryRule.rootTableWrites(source, writeLabels)

        test("catches root-table writes whatever their name, including the ones a verb list missed") {
            val writes =
                listOf(
                    "db.booksQueries.clearManagedCover(id)",
                    "db.booksQueries.setCoverColumns(a, b, id)",
                    "db.booksQueries.touchRevision(rev, id)",
                    "db.booksQueries.reviveById(id)",
                    "sql.seriesQueries.setHierarchy(parent, id)",
                    "db.contributorsQueries.repointMergedInto(to, from)",
                    "db.collectionsQueries.setType(type, id)",
                )
            writes.filter { detect(it).isEmpty() } shouldBe emptyList()
        }

        test("does not fire on reads, on a non-syncable table, on an exempt column write, or in a comment") {
            val clean =
                listOf(
                    "db.booksQueries.selectIdsAboveRevision(cursor)",
                    "db.tagsQueries.existsById(id)",
                    "sql.usersQueries.updatePassword(hash, id)",
                    "sql.bookRatingsQueries.setHardcoverSeen(0, book, user)",
                    "// db.booksQueries.clearManagedCover(id)",
                )
            clean.filter { detect(it).isNotEmpty() } shouldBe emptyList()
        }

        test("names the queries wrapper and the label it caught") {
            detect("db.booksQueries.clearManagedCover(id)") shouldContainExactly listOf("booksQueries.clearManagedCover")
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
            writeLabelsIn(sql) shouldBe setOf("touch")
        }
    })
