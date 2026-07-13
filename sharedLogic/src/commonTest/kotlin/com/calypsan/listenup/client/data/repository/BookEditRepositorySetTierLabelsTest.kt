package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.CollectionRpcFactory
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.core.BookId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.test.runTest

class BookEditRepositorySetTierLabelsTest :
    FunSpec({
        test("setBookTierLabels dispatches to BookService over RPC") {
            runTest {
                val service = mock<BookService>(MockMode.autoUnit)
                val rpcFactory = mock<BookRpcFactory>(MockMode.autoUnit)
                everySuspend { rpcFactory.bookService() } returns service
                everySuspend {
                    service.setBookTierLabels(BookId("b1"), "Book", "Part")
                } returns AppResult.Success(Unit)

                val offlineEditor =
                    OfflineEditor(
                        pendingQueue =
                            PendingOperationQueue(
                                dao = mock<PendingOperationV2Dao>(MockMode.autoUnit),
                                sender = PendingOperationSender { AppResult.Success(Unit) },
                            ),
                        transactionRunner = mock<TransactionRunner>(MockMode.autoUnit),
                        authSession = mock<AuthSession>(MockMode.autoUnit),
                    )

                val repo =
                    BookEditRepositoryImpl(
                        bookRpcFactory = rpcFactory,
                        collectionRpcFactory = mock<CollectionRpcFactory>(MockMode.autoUnit),
                        bookDao = mock<BookDao>(MockMode.autoUnit),
                        offlineEditor = offlineEditor,
                    )
                repo.setBookTierLabels(BookId("b1"), "Book", "Part")

                verifySuspend { service.setBookTierLabels(BookId("b1"), "Book", "Part") }
            }
        }
    })
