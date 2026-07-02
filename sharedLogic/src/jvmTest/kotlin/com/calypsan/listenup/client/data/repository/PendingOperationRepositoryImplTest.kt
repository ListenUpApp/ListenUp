package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.model.PendingOperationStatus
import com.calypsan.listenup.client.domain.model.PendingOperationType
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Tests for [PendingOperationRepositoryImpl] — the read-model over the live
 * [PendingOperationQueue] that backs the sync indicator.
 */
class PendingOperationRepositoryImplTest :
    FunSpec({

        test("observeVisibleOperations excludes silent domains and maps status PENDING") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                        nowMillis = { 1_000L },
                    )
                val repo = PendingOperationRepositoryImpl(queue = queue)
                queue.enqueue("books", "b1", "update", "{}", "u1")
                queue.enqueue("playback_positions", "b1", "upsert", "{}", "u1")
                queue.enqueue("listening_events", "e1", "upsert", "{}", "u1")
                queue.enqueue("preferences", "u1", "update", "{}", "u1")

                val visible = repo.observeVisibleOperations().first()

                visible shouldHaveSize 1
                visible.single().operationType shouldBe PendingOperationType.BOOK_UPDATE
                visible.single().status shouldBe PendingOperationStatus.PENDING
                db.close()
            }
        }

        test("observeFailedOperations maps terminal ops with FAILED status, error code, and OTHER fallback") {
            runTest {
                val db = createInMemoryTestDatabase()
                val error = SyncError.NotFound(domain = "books", entityId = "b1")
                val sender = PendingOperationSender { AppResult.Failure(error) }
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )
                val repo = PendingOperationRepositoryImpl(queue = queue)
                queue.enqueue("books", "b1", "update", "{}", "u1")
                queue.enqueue("tags", "t1", "update", "{}", "u1")
                queue.drain()

                val failed = repo.observeFailedOperations().first()

                failed shouldHaveSize 2
                failed.forEach { it.status shouldBe PendingOperationStatus.FAILED }
                failed.forEach { it.lastError shouldBe error.code }
                failed.map { it.operationType } shouldContainExactlyInAnyOrder
                    listOf(PendingOperationType.BOOK_UPDATE, PendingOperationType.OTHER)
                db.close()
            }
        }

        test("retry and dismiss delegate to the queue") {
            runTest {
                val db = createInMemoryTestDatabase()
                val error = SyncError.NotFound(domain = "books", entityId = "b1")
                val sender = PendingOperationSender { AppResult.Failure(error) }
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = sender,
                        nowMillis = { 1_000L },
                    )
                val repo = PendingOperationRepositoryImpl(queue = queue)
                val opId = queue.enqueue("books", "b1", "update", "{}", "u1")
                queue.drain()
                db.pendingOperationV2Dao().nextDispatchable() shouldHaveSize 0

                repo.retry(opId)

                db.pendingOperationV2Dao().nextDispatchable().map { it.clientOpId } shouldContainExactlyInAnyOrder listOf(opId)

                repo.dismiss(opId)

                db.pendingOperationV2Dao().get(opId) shouldBe null
                db.close()
            }
        }
    })
