package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
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
                val authSession: AuthSession = mock()
                everySuspend { authSession.getUserId() } returns "u1"

                val editor = OfflineEditor(pendingQueue = queue, transactionRunner = txRunner, authSession = authSession)

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
                val authSession: AuthSession = mock()
                everySuspend { authSession.getUserId() } returns null

                val editor = OfflineEditor(pendingQueue = queue, transactionRunner = txRunner, authSession = authSession)

                var applied = false
                val result = editor.edit(BookEdit, entityId = "book1", patch = BookUpdate(title = "x")) { applied = true }

                result.shouldBeInstanceOf<AppResult.Failure>()
                applied shouldBe false
                db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).firstOrNull() shouldBe null
                db.close()
            }
        }
    })
