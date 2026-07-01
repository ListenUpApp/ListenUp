package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class OfflineEditorTest :
    FunSpec({

        test("applies the local write inside the transaction and enqueues the encoded patch") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                var applied = false
                val txRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    }
                val editor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = FakeAuthSession(userId = "u1"),
                    )

                val patch = BookUpdate(title = "New Title")
                val result = editor.edit(BookEdit, entityId = "book1", patch = patch) { applied = true }

                result shouldBe AppResult.Success(Unit)
                applied shouldBe true
                val op = db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).firstOrNull()
                op?.domainName shouldBe "books"
                op?.entityId shouldBe "book1"
                op?.opType shouldBe "update"
                op?.payload shouldBe contractJson.encodeToString(BookUpdate.serializer(), patch)
                db.close()
            }
        }

        test("returns Failure and does NOT enqueue when no user is signed in") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val txRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    }
                val editor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = FakeAuthSession(userId = null),
                    )

                var applied = false
                val result = editor.edit(BookEdit, entityId = "book1", patch = BookUpdate(title = "x")) { applied = true }

                result.shouldBeInstanceOf<AppResult.Failure>()
                applied shouldBe false
                db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).firstOrNull() shouldBe null
                db.close()
            }
        }

        test("a throwing local write rolls back the whole edit — no pending op is enqueued") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                // The REAL Room transaction runner: enqueue is coupled inside applyLocally's
                // transaction, so a throw from applyLocally must abort the enqueue too.
                val editor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = RoomTransactionRunner(db),
                        authSession = FakeAuthSession(userId = "u1"),
                    )

                shouldThrow<IllegalStateException> {
                    editor.edit(BookEdit, entityId = "book1", patch = BookUpdate(title = "x")) {
                        error("local write blew up")
                    }
                }

                db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).firstOrNull() shouldBe null
                db.close()
            }
        }
    })
