package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import dev.mokkery.MockMode
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.test.runTest

/**
 * [BookEditRepositoryImpl.setBookTierLabels] is offline-first like every other book edit: it enqueues a
 * [BookMutation.SetTierLabels] on the `books` outbox channel and applies the optimistic Room merge in the
 * same transaction, so a tier-label rename persists and replays on reconnect instead of failing offline.
 */
class BookEditRepositorySetTierLabelsTest :
    FunSpec({
        test("setBookTierLabels applies a SetTierLabels mutation through the offline editor") {
            runTest {
                val localApply = mock<BookMutationLocalApply>(MockMode.autoUnit)
                val offlineEditor =
                    OfflineEditor(
                        pendingQueue =
                            PendingOperationQueue(
                                dao = mock<PendingOperationV2Dao>(MockMode.autoUnit),
                                sender = PendingOperationSender { AppResult.Success(Unit) },
                            ),
                        transactionRunner =
                            object : TransactionRunner {
                                override suspend fun <R> atomically(block: suspend () -> R): R = block()
                            },
                        authSession = FakeAuthSession(userId = "u1"),
                    )
                val repo =
                    BookEditRepositoryImpl(
                        offlineEditor = offlineEditor,
                        localApply = localApply,
                        bookDao = mock<BookDao>(MockMode.autoUnit),
                    )

                repo.setBookTierLabels(BookId("b1"), "Book", "Part")

                verifySuspend {
                    localApply.apply(BookId("b1"), BookMutation.SetTierLabels("Book", "Part"))
                }
            }
        }
    })
