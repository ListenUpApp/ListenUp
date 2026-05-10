package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class PendingOperationV2DaoTest :
    FunSpec({

        fun row(
            id: String,
            domain: String,
            entity: String,
            enqueued: Long,
            failureCount: Int = 0,
            owner: String = "u1",
        ) = PendingOperationV2Entity(
            clientOpId = id,
            domainName = domain,
            entityId = entity,
            opType = "upsert",
            payload = "{}",
            enqueuedAt = enqueued,
            lastAttemptAt = null,
            failureCount = failureCount,
            lastError = null,
            ownerUserId = owner,
        )

        test("nextDispatchable returns earliest-enqueued op per (domain, entity) group") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val firstTagsT1 = 100L
                val secondTagsT1 = 200L
                val firstTagsT2 = 150L
                dao.insert(row("a", "tags", "t1", firstTagsT1))
                dao.insert(row("b", "tags", "t1", secondTagsT1))
                dao.insert(row("c", "tags", "t2", firstTagsT2))
                val next = dao.nextDispatchable()
                next.map { it.clientOpId } shouldContainExactlyInAnyOrder listOf("a", "c")
                db.close()
            }
        }

        test("nextDispatchable excludes ops past the retry budget") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val baseTime = 100L
                val maxAttempts = 5
                dao.insert(row("retryable", "tags", "t1", baseTime, failureCount = maxAttempts))
                dao.insert(row("exhausted", "tags", "t2", baseTime, failureCount = maxAttempts + 1))
                val next = dao.nextDispatchable()
                next.map { it.clientOpId } shouldContainExactly listOf("retryable")
                db.close()
            }
        }

        test("nextDispatchable orders globally by enqueuedAt across groups") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val laterEntity = 300L
                val earlierEntity = 100L
                dao.insert(row("late", "tags", "t1", laterEntity))
                dao.insert(row("early", "tags", "t2", earlierEntity))
                val next = dao.nextDispatchable()
                next.map { it.clientOpId } shouldContainExactly listOf("early", "late")
                db.close()
            }
        }

        test("deleteAllExcept removes ops not owned by keepUserId") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val baseTime = 100L
                dao.insert(row("mine", "tags", "t1", baseTime, owner = "u1"))
                dao.insert(row("theirs", "tags", "t2", baseTime, owner = "u2"))
                dao.deleteAllExcept(keepUserId = "u1")
                dao.get("mine") shouldNotBe null
                dao.get("theirs") shouldBe null
                db.close()
            }
        }

        test("observeQueueDepth emits live total count") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val baseTime = 100L
                dao.insert(row("a", "tags", "t1", baseTime))
                dao.insert(row("b", "tags", "t2", baseTime))
                val expectedDepth = 2
                dao.observeQueueDepth().first() shouldBe expectedDepth
                db.close()
            }
        }
    })
