package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Self-test for [ClientDaoWritesGoThroughDataLayerRule]'s matcher, against planted samples. */
class ClientDaoWritesGoThroughDataLayerRuleSelfTest :
    FunSpec({

        fun detect(source: String): List<String>? =
            ClientDaoWritesGoThroughDataLayerRule.DAO_WRITE_CALL
                .find(source)
                ?.run { groupValues.drop(1) }

        test("catches every write verb on a DAO field") {
            val writes =
                listOf(
                    "bookDao.insert(row)",
                    "bookDao.update(row)",
                    "bookDao.upsert(row)",
                    "bookDao.deleteById(id)",
                    "bookDao.replaceAll(rows)",
                    "bookDao.markSynced(id)",
                    "bookDao.setProgress(id, 1)",
                    "bookDao.saveDraft(row)",
                    "bookDao.clearAll()",
                    "bookDao.raiseFlag(id)",
                )
            writes.filter { detect(it) == null } shouldBe emptyList()
        }

        test("catches the call form and a multi-line call") {
            detect("db.bookDao().upsert(row)") shouldBe listOf("bookDao", "upsert")
            detect("bookDao.upsert (\n row)") shouldBe listOf("bookDao", "upsert")
        }

        test("does not fire on reads or on non-DAO receivers") {
            val reads =
                listOf(
                    "bookDao.getById(id)",
                    "bookDao.observeAll()",
                    "db.bookDao().selectIds()",
                    "bookRepository.upsert(row)",
                    "cache.clear()",
                )
            reads.filter { detect(it) != null } shouldBe emptyList()
        }
    })
