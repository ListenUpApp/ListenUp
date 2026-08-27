package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ChapterId
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ChapterDao
import com.calypsan.listenup.client.data.local.db.ChapterEntity
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.api.sync.BookSyncPayload
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.client.data.local.db.BookTierLabelRow
import com.calypsan.listenup.client.domain.model.TierLabels

/**
 * Verifies that [BookRepositoryImpl.observeChapters] maps [ChapterEntity] rows to
 * domain [Chapter] models and re-emits whenever the DAO Flow emits.
 */
class ObserveChaptersTest :
    FunSpec({

        fun createRepository(
            chapterDao: ChapterDao,
            bookDao: BookDao = mock<BookDao>(MockMode.autoUnit),
        ): BookRepositoryImpl {
            val networkMonitor = mock<NetworkMonitor>()
            every { networkMonitor.isOnline() } returns false
            return BookRepositoryImpl(
                bookDao = bookDao,
                chapterDao = chapterDao,
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
                channel = RpcChannel.forTest(mock<BookService>(MockMode.autoUnit)),
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(MockMode.autoUnit),
            )
        }

        test("observeChapters maps entities to domain models, ordered") {
            runTest {
                val chapterDao = mock<ChapterDao>(MockMode.autoUnit)
                every { chapterDao.observeChaptersForBook(BookId("b1")) } returns
                    flowOf(
                        listOf(
                            ChapterEntity(id = ChapterId("c1"), bookId = BookId("b1"), title = "Intro", duration = 1000L, startTime = 0L),
                            ChapterEntity(id = ChapterId("c2"), bookId = BookId("b1"), title = "Two", duration = 1000L, startTime = 1000L),
                        ),
                    )
                val repo = createRepository(chapterDao)

                val result = repo.observeChapters("b1").first()

                result.map { it.title } shouldBe listOf("Intro", "Two")
                result.map { it.id } shouldBe listOf("c1", "c2")
            }
        }

        test("observeChapters emits empty list when book has no chapters") {
            runTest {
                val chapterDao = mock<ChapterDao>(MockMode.autoUnit)
                every { chapterDao.observeChaptersForBook(BookId("b2")) } returns flowOf(emptyList())
                val repo = createRepository(chapterDao)

                val result = repo.observeChapters("b2").first()

                result shouldBe emptyList()
            }
        }

        test("observeChapters carries the section headers that drive grouping") {
            runTest {
                val chapterDao = mock<ChapterDao>(MockMode.autoUnit)
                every { chapterDao.observeChaptersForBook(BookId("b4")) } returns
                    flowOf(
                        listOf(
                            ChapterEntity(
                                id = ChapterId("c1"),
                                bookId = BookId("b4"),
                                title = "Prologue",
                                duration = 1000L,
                                startTime = 0L,
                                partTitle = "Part One",
                                bookTitle = "Book One",
                            ),
                            ChapterEntity(
                                id = ChapterId("c2"),
                                bookId = BookId("b4"),
                                title = "Chapter 1",
                                duration = 1000L,
                                startTime = 1000L,
                            ),
                        ),
                    )
                val repo = createRepository(chapterDao)

                val result = repo.observeChapters("b4").first()

                result[0].partTitle shouldBe "Part One"
                result[0].bookTitle shouldBe "Book One"
                result[1].partTitle shouldBe null
            }
        }

        test("observeBookTierLabels reads the book's own vocabulary") {
            runTest {
                val bookDao = mock<BookDao>(MockMode.autoUnit)
                every { bookDao.observeTierLabels(BookId("b1")) } returns
                    flowOf(BookTierLabelRow(bookTierLabel = "Volume", partTierLabel = "Sequence"))
                val repo = createRepository(mock<ChapterDao>(MockMode.autoUnit), bookDao)

                repo.observeBookTierLabels("b1").first() shouldBe
                    TierLabels(bookTierLabel = "Volume", partTierLabel = "Sequence")
            }
        }

        test("observeBookTierLabels reports an absent book as naming neither tier") {
            // An unknown book has no vocabulary, which is what every reader would conclude from a
            // null anyway — collapsing it here saves each of them an identical branch.
            runTest {
                val bookDao = mock<BookDao>(MockMode.autoUnit)
                every { bookDao.observeTierLabels(BookId("gone")) } returns flowOf(null)
                val repo = createRepository(mock<ChapterDao>(MockMode.autoUnit), bookDao)

                repo.observeBookTierLabels("gone").first() shouldBe TierLabels.None
            }
        }

        test("observeChapters maps all domain fields correctly") {
            runTest {
                val chapterDao = mock<ChapterDao>(MockMode.autoUnit)
                every { chapterDao.observeChaptersForBook(BookId("b3")) } returns
                    flowOf(
                        listOf(
                            ChapterEntity(id = ChapterId("c99"), bookId = BookId("b3"), title = "Finale", duration = 5000L, startTime = 9000L),
                        ),
                    )
                val repo = createRepository(chapterDao)

                val result = repo.observeChapters("b3").first()

                result.size shouldBe 1
                result[0].id shouldBe "c99"
                result[0].title shouldBe "Finale"
                result[0].duration shouldBe 5000L
                result[0].startTime shouldBe 9000L
            }
        }
    })
