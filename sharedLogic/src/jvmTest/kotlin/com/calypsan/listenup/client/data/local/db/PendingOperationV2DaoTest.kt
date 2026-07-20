package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.data.sync.MAX_RETRYABLE_ATTEMPTS
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

        fun deadLetterFixture(
            clientOpId: String,
            failureCount: Int = 0,
            enqueuedAt: Long = 1_000L,
            lastAttemptAt: Long? = null,
        ) = PendingOperationV2Entity(
            clientOpId = clientOpId,
            domainName = "books",
            entityId = "e-$clientOpId",
            opType = "update",
            payload = "{}",
            enqueuedAt = enqueuedAt,
            lastAttemptAt = lastAttemptAt,
            failureCount = failureCount,
            lastError = null,
            ownerUserId = "u1",
        )

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

        test("nextDispatchable breaks same-enqueuedAt ties by clientOpId, not insertion order") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val tiedEnqueuedAt = 500L
                // Insert the alphabetically-larger clientOpId FIRST so a pass here can't be explained
                // by SQLite happening to pick the earliest-inserted row.
                dao.insert(row("z-op", "tags", "t1", tiedEnqueuedAt))
                dao.insert(row("a-op", "tags", "t1", tiedEnqueuedAt))
                val next = dao.nextDispatchable()
                next.map { it.clientOpId } shouldContainExactly listOf("a-op")
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

        test("nextDispatchable with a non-null ownerUserId excludes ops owned by a different user") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val baseTime = 100L
                dao.insert(row("mine", "tags", "t1", baseTime, owner = "u1"))
                dao.insert(row("theirs", "tags", "t2", baseTime, owner = "u2"))
                val next = dao.nextDispatchable(ownerUserId = "u1")
                next.map { it.clientOpId } shouldContainExactly listOf("mine")
                db.close()
            }
        }

        test("nextDispatchable with a null ownerUserId is unscoped") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val baseTime = 100L
                dao.insert(row("mine", "tags", "t1", baseTime, owner = "u1"))
                dao.insert(row("theirs", "tags", "t2", baseTime, owner = "u2"))
                val next = dao.nextDispatchable(ownerUserId = null)
                next.map { it.clientOpId } shouldContainExactlyInAnyOrder listOf("mine", "theirs")
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

        test("observePending emits only ops within retry budget, oldest first") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val maxAttempts = 5
                dao.insert(row("healthy", "tags", "t1", 100L, failureCount = 0))
                dao.insert(row("terminal", "tags", "t2", 200L, failureCount = maxAttempts + 1))
                val pending = dao.observePending().first()
                pending.map { it.clientOpId } shouldContainExactly listOf("healthy")
                db.close()
            }
        }

        test("observeFailed emits only ops past the retry budget") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                val maxAttempts = 5
                dao.insert(row("healthy", "tags", "t1", 100L, failureCount = 0))
                dao.insert(row("terminal", "tags", "t2", 200L, failureCount = maxAttempts + 1))
                val failed = dao.observeFailed().first()
                failed.map { it.clientOpId } shouldContainExactly listOf("terminal")
                db.close()
            }
        }

        test("resetFailureCount zeroes failureCount and clears lastError") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                dao.insert(
                    PendingOperationV2Entity(
                        clientOpId = "terminal",
                        domainName = "tags",
                        entityId = "t1",
                        opType = "upsert",
                        payload = "{}",
                        enqueuedAt = 100L,
                        lastAttemptAt = 200L,
                        failureCount = 6,
                        lastError = "SOME_CODE",
                        ownerUserId = "u1",
                    ),
                )
                dao.resetFailureCount("terminal")
                val updated = dao.get("terminal")
                updated?.failureCount shouldBe 0
                updated?.lastError shouldBe null
                db.close()
            }
        }

        test("observeQueueDepth counts only dispatchable ops, excluding dead letters") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                dao.insert(deadLetterFixture("live"))
                dao.insert(deadLetterFixture("dead", failureCount = MAX_RETRYABLE_ATTEMPTS + 1))

                val expectedDepth = 1
                dao.observeQueueDepth().first() shouldBe expectedDepth
                db.close()
            }
        }

        test("gcDeadLetters deletes terminal ops past the cutoff and keeps young dead letters and old live ops") {
            runTest {
                val db = createInMemoryTestDatabase()
                val dao = db.pendingOperationV2Dao()
                dao.insert(
                    deadLetterFixture("dead-old", failureCount = MAX_RETRYABLE_ATTEMPTS + 1, lastAttemptAt = 1_000L),
                )
                dao.insert(
                    deadLetterFixture("dead-young", failureCount = MAX_RETRYABLE_ATTEMPTS + 1, lastAttemptAt = 9_000L),
                )
                dao.insert(deadLetterFixture("live-old", failureCount = 0, enqueuedAt = 500L))
                dao.insert(
                    deadLetterFixture("dead-never-attempted", failureCount = MAX_RETRYABLE_ATTEMPTS + 1, enqueuedAt = 900L),
                )

                dao.gcDeadLetters(cutoffMillis = 5_000L)

                dao.get("dead-old") shouldBe null
                dao.get("dead-never-attempted") shouldBe null
                dao.get("dead-young") shouldNotBe null
                dao.get("live-old") shouldNotBe null
                db.close()
            }
        }
    })
