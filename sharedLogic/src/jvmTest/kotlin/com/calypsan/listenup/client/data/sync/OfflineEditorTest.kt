package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.coroutines.cancellation.CancellationException
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
                val result = editor.edit(OutboxChannels.Books, entityId = "book1", patch = patch) { applied = true }

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

        test("the enqueue signal fires only AFTER the transaction commits, and a drain then sees the op") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                val initialSignal = queue.observeEnqueueSignal().value
                // A runner that snapshots the signal DURING the transaction — after the enqueue row
                // write, before commit. The fix defers the tick to post-commit, so this must equal
                // the initial value; the bug ticked inside enqueue, so it would already be +1 here.
                var signalDuringTx: Long? = null
                val txRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R {
                            val result = block()
                            signalDuringTx = queue.observeEnqueueSignal().value
                            return result
                        }
                    }
                val editor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = FakeAuthSession(userId = "u1"),
                    )

                editor.edit(OutboxChannels.Books, entityId = "book1", patch = BookUpdate(title = "New Title")) { }

                // No pre-commit signal: the drain collector can't wake against pre-commit state.
                signalDuringTx shouldBe initialSignal
                // The signal fired exactly once, post-commit.
                queue.observeEnqueueSignal().value shouldBe initialSignal + 1
                // And a drain triggered by that signal now finds the committed op.
                queue.drain().sent shouldBe 1
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
                val result = editor.edit(OutboxChannels.Books, entityId = "book1", patch = BookUpdate(title = "x")) { applied = true }

                result.shouldBeInstanceOf<AppResult.Failure>()
                applied shouldBe false
                db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).firstOrNull() shouldBe null
                db.close()
            }
        }

        test("a throwing local write yields Failure and rolls back — no pending op is enqueued") {
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

                val result =
                    editor.edit(OutboxChannels.Books, entityId = "book1", patch = BookUpdate(title = "x")) {
                        error("local write blew up")
                    }

                result.shouldBeInstanceOf<AppResult.Failure>()
                db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).firstOrNull() shouldBe null
                db.close()
            }
        }

        test("CancellationException from the local write propagates — it is never folded into Failure") {
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
                        authSession = FakeAuthSession(userId = "u1"),
                    )

                shouldThrow<CancellationException> {
                    editor.edit(OutboxChannels.Books, entityId = "book1", patch = BookUpdate(title = "x")) {
                        throw CancellationException("cancelled")
                    }
                }

                db.close()
            }
        }

        test("an op kind the channel does not declare folds to InternalError, not ValidationError") {
            runTest {
                val db = createInMemoryTestDatabase()
                val queue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    )
                // The REAL Room transaction runner: enqueue's check-failure must roll back
                // applyLocally's write in the same transaction, exactly like a throw would.
                val editor =
                    OfflineEditor(
                        pendingQueue = queue,
                        transactionRunner = RoomTransactionRunner(db),
                        authSession = FakeAuthSession(userId = "u1"),
                    )

                // OutboxChannels.Books declares only OpKind.Update — Upsert is a programmer error,
                // not bad user input, so the failure must be an InternalError (detail in debugInfo),
                // never a ValidationError (whose message is user-facing).
                val result =
                    editor.edit(
                        OutboxChannels.Books,
                        entityId = "book1",
                        patch = BookUpdate(title = "x"),
                        op = OpKind.Upsert,
                    ) { }

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<InternalError>()
                db.pendingOperationV2Dao().nextDispatchable(maxAttempts = 5).firstOrNull() shouldBe null
                db.close()
            }
        }
    })
