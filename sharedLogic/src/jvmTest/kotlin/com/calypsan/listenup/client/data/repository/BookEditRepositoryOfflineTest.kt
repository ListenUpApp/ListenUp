package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.CollectionRpcFactory
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class BookEditRepositoryOfflineTest :
    FunSpec({
        test("offline book edit persists to Room and enqueues a pending op without ServerConnectError") {
            runTest {
                val db = createInMemoryTestDatabase()
                val bookId = BookId("book1")
                db.bookDao().upsert(
                    BookEntity(
                        id = bookId,
                        libraryId = LibraryId("lib1"),
                        folderId = FolderId("folder1"),
                        title = "Old Title",
                        totalDuration = 0L,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                )

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
                everySuspend { authSession.getUserId() } returns "u1"

                val repo =
                    BookEditRepositoryImpl(
                        bookRpcFactory = mock<BookRpcFactory>(),
                        collectionRpcFactory = mock<CollectionRpcFactory>(),
                        bookDao = db.bookDao(),
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = authSession,
                    )

                val result = repo.updateBook(bookId, BookUpdate(title = "New Title"))

                result shouldBe AppResult.Success(Unit)
                db.bookDao().getById(bookId)?.title shouldBe "New Title"
                db
                    .pendingOperationV2Dao()
                    .nextDispatchable(maxAttempts = 5)
                    .firstOrNull()
                    ?.domainName shouldBe "books"
                db.close()
            }
        }

        test("editing title and description records TITLE+DESCRIPTION provenance locally and in the pushed patch") {
            runTest {
                val db = createInMemoryTestDatabase()
                val bookId = BookId("book1")
                db.bookDao().upsert(
                    BookEntity(
                        id = bookId,
                        libraryId = LibraryId("lib1"),
                        folderId = FolderId("folder1"),
                        title = "Old Title",
                        totalDuration = 0L,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                )

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
                everySuspend { authSession.getUserId() } returns "u1"

                val repo =
                    BookEditRepositoryImpl(
                        bookRpcFactory = mock<BookRpcFactory>(),
                        collectionRpcFactory = mock<CollectionRpcFactory>(),
                        bookDao = db.bookDao(),
                        pendingQueue = queue,
                        transactionRunner = txRunner,
                        authSession = authSession,
                    )

                repo.updateBook(bookId, BookUpdate(title = "New Title", description = "New Desc"))

                // Local: the optimistic Room write mirrors the server's per-field provenance union.
                db.bookDao().getById(bookId)?.userEditedFields shouldBe
                    setOf(UserEditedField.TITLE, UserEditedField.DESCRIPTION)

                // Pushed: the queued patch carries the edited scalars, so the server unions the same set.
                val pushedPayload =
                    db
                        .pendingOperationV2Dao()
                        .nextDispatchable(maxAttempts = 5)
                        .first()
                        .payload
                val pushedPatch = contractJson.decodeFromString(BookUpdate.serializer(), pushedPayload)
                pushedPatch.title shouldBe "New Title"
                pushedPatch.description shouldBe "New Desc"

                db.close()
            }
        }
    })
