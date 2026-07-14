package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookTierLabelsRow
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.TagRepository
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Verifies that [BookRepositoryImpl.observeBookTierLabels] maps [BookTierLabelsRow] rows to
 * the domain [com.calypsan.listenup.domain.TierLabels] model, including the absent-book default.
 */
class ObserveBookTierLabelsTest :
    FunSpec({

        fun createRepository(bookDao: BookDao): BookRepositoryImpl {
            val networkMonitor = mock<NetworkMonitor>()
            every { networkMonitor.isOnline() } returns false
            return BookRepositoryImpl(
                bookDao = bookDao,
                chapterDao = mock<ChapterDao>(MockMode.autoUnit),
                audioFileDao = mock<AudioFileDao>(MockMode.autoUnit),
                searchDao = mock<SearchDao>(MockMode.autoUnit),
                transactionRunner = mock<TransactionRunner>(MockMode.autoUnit),
                imageStorage = mock<ImageStorage>(MockMode.autoUnit),
                joinSources =
                    BookDetailJoinSources(
                        genreRepository = mock<GenreRepository>(MockMode.autoUnit),
                        tagRepository = mock<TagRepository>(MockMode.autoUnit),
                        moodRepository = mock<MoodRepository>(MockMode.autoUnit),
                    ),
                networkMonitor = networkMonitor,
                channel = mock<RpcChannel<BookService>>(MockMode.autoUnit),
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(MockMode.autoUnit),
            )
        }

        test("observeBookTierLabels maps a present row to TierLabels") {
            runTest {
                val bookDao = mock<BookDao>(MockMode.autoUnit)
                every { bookDao.observeTierLabels(BookId("b1")) } returns
                    flowOf(BookTierLabelsRow(bookTierLabel = "Book", partTierLabel = "Part"))
                val repo = createRepository(bookDao)

                repo.observeBookTierLabels("b1").test {
                    val item = awaitItem()
                    item.bookTierLabel shouldBe "Book"
                    item.partTierLabel shouldBe "Part"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("observeBookTierLabels defaults to null/null when the book row is absent") {
            runTest {
                val bookDao = mock<BookDao>(MockMode.autoUnit)
                every { bookDao.observeTierLabels(BookId("missing")) } returns flowOf(null)
                val repo = createRepository(bookDao)

                repo.observeBookTierLabels("missing").test {
                    val item = awaitItem()
                    item.bookTierLabel shouldBe null
                    item.partTierLabel shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
