package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookContributorDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSeriesDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.PendingOperationV2Dao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.core.BookId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * [BookEditRepositoryImpl.setBookTierLabels] is offline-first like every other book edit: it enqueues a
 * `BookMutation.SetTierLabels` on the `books` outbox channel and applies the optimistic Room merge in the
 * same transaction, so a tier-label rename persists and replays on reconnect instead of failing offline.
 *
 * A `Success` result is a real assertion, not a smoke test: the enqueue path `check`s the op is one the
 * `books` channel declares and serializes the mutation through `BookMutation.serializer()`, so a
 * mis-registered or unserializable [com.calypsan.listenup.api.dto.BookMutation.SetTierLabels] would fold
 * to `AppResult.Failure` here.
 */
class BookEditRepositorySetTierLabelsTest :
    FunSpec({
        test("setBookTierLabels enqueues a valid, serializable SetTierLabels op and succeeds") {
            runTest {
                // localApply.applyTierLabels reads the book row before merging; absent (null) is the
                // valid "not yet in Room" path, which no-ops the optimistic write and still enqueues.
                val applyBookDao = mock<BookDao>(MockMode.autoUnit)
                everySuspend { applyBookDao.getById(BookId("b1")) } returns null
                val localApply =
                    BookMutationLocalApply(
                        bookDao = applyBookDao,
                        bookContributorDao = mock<BookContributorDao>(MockMode.autoUnit),
                        contributorDao = mock<ContributorDao>(MockMode.autoUnit),
                        bookSeriesDao = mock<BookSeriesDao>(MockMode.autoUnit),
                        seriesDao = mock<SeriesDao>(MockMode.autoUnit),
                        genreDao = mock<GenreDao>(MockMode.autoUnit),
                        chapterDao = mock<ChapterDao>(MockMode.autoUnit),
                        collectionBookDao = mock<CollectionBookDao>(MockMode.autoUnit),
                    )
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

                val result = repo.setBookTierLabels(BookId("b1"), "Book", "Part")

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }
    })
