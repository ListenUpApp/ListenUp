package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Self-test for [ClientDaoWritesGoThroughDataLayerRule]'s matcher, against planted samples — using the write set the
 * rule itself derives from the DAOs, so a sample here is caught exactly when the rule would catch it.
 */
class ClientDaoWritesGoThroughDataLayerRuleSelfTest :
    FunSpec({

        fun detect(source: String): List<String> = ClientDaoWritesGoThroughDataLayerRule.daoWriteCalls(source)

        test("catches writes whatever their verb, including the ones a verb list missed") {
            val writes =
                listOf(
                    "bookDao.upsert(row)",
                    "bookDao.deleteById(id)",
                    "bookDao.softDelete(id, 1L, 2L)",
                    "tagDao.tombstoneByIds(ids, 1L)",
                    "pendingOperationV2Dao.claimForTransfer(id)",
                    "syncCursorDao.advanceExistingCursor(domain, 1L)",
                )
            writes.filter { detect(it).isEmpty() } shouldBe emptyList()
        }

        test("catches the call form and a multi-line call") {
            detect("db.bookDao().upsert(row)") shouldBe listOf("bookDao.upsert")
            detect("bookDao.softDelete (\n id)") shouldBe listOf("bookDao.softDelete")
        }

        test("does not fire on reads, on non-DAO receivers, or in a comment") {
            val reads =
                listOf(
                    "bookDao.getById(id)",
                    "bookDao.observeAll()",
                    "bookRepository.upsert(row)",
                    "cache.clear()",
                    "// bookDao.softDelete(id)",
                )
            reads.filter { detect(it).isNotEmpty() } shouldBe emptyList()
        }

        test("a @Query is a write when its SQL starts with a write verb") {
            ClientDaoWritesGoThroughDataLayerRule.isWriteSql("@Query(\"UPDATE books SET x = 1\")") shouldBe true
            // A literal `"""` cannot be written inside a raw string, so build it from a char.
            val tripleQuote = "${'"'}${'"'}${'"'}"
            val multiLine =
                """
                @Query(
                    $tripleQuote
                    delete FROM books
                $tripleQuote
                )
                """.trimIndent()
            ClientDaoWritesGoThroughDataLayerRule.isWriteSql(multiLine) shouldBe true
            ClientDaoWritesGoThroughDataLayerRule.isWriteSql("@Query(\"SELECT * FROM books\")") shouldBe false
        }
    })
