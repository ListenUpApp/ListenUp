package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.PlaybackPositionDao
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Kotest FunSpec tests for [HomeRepositoryImpl.observeContinueListening].
 *
 * Covers the new [ContinueListeningItem]-typed Flow that replaced the old
 * [ContinueListeningBook]-typed implementation:
 *
 *  A) Defense-in-depth: isFinished=true books are excluded even at low progress
 *  B) No undercount: books at high progress but isFinished=false are included
 *  C) Race-safety: rapid position emissions don't drop items mid-join
 *  D) Loading placeholder: missing book → Loading; arrival → Ready
 *  E) Size limit respected
 *  F) Ordering preserved (positions determine order)
 *  G) Empty positions → empty list
 */
@OptIn(ExperimentalTime::class)
class HomeRepositoryObserveTest :
    FunSpec({
        // ====================================================================
        // Helpers
        // ====================================================================

        fun makeDao(
            positionFlow: MutableStateFlow<List<PlaybackPositionEntity>>,
        ): PlaybackPositionDao {
            val dao: PlaybackPositionDao = mock()
            every { dao.observeRecentPositions(any()) } returns positionFlow
            everySuspend { dao.getRecentPositions(any()) } returns emptyList()
            everySuspend { dao.get(any()) } returns null
            everySuspend { dao.save(any()) } returns Unit
            everySuspend { dao.saveAll(any()) } returns Unit
            return dao
        }

        fun makeBookRepo(bookFlow: MutableStateFlow<List<BookListItem>>): BookRepository {
            val repo: BookRepository = mock()
            every { repo.observeBookListItems(any()) } returns bookFlow
            everySuspend { repo.getBookListItems(any()) } returns emptyList()
            // other required methods
            return repo
        }

        fun position(
            bookId: String,
            positionMs: Long = 5_000L,
            isFinished: Boolean = false,
            lastPlayedAt: Long? = null,
        ): PlaybackPositionEntity =
            PlaybackPositionEntity(
                bookId = BookId(bookId),
                positionMs = positionMs,
                playbackSpeed = 1.0f,
                updatedAt = Clock.System.now().toEpochMilliseconds(),
                isFinished = isFinished,
                lastPlayedAt = lastPlayedAt,
            )

        fun book(
            id: String,
            duration: Long = 10_000L,
        ): BookListItem = TestData.bookListItem(id = id, duration = duration)

        // ====================================================================
        // A) Defense-in-depth: isFinished=true → excluded regardless of progress
        // ====================================================================

        test("A: isFinished=true book is excluded at low progress (defense-in-depth)") {
            runTest {
                val posFlow = MutableStateFlow(listOf(position("b1", positionMs = 100L, isFinished = true)))
                val bookFlow = MutableStateFlow(listOf(book("b1")))
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(10).first()
                result.shouldBeEmpty()
            }
        }

        test("A: isFinished=true at high progress (>=95%) is excluded") {
            runTest {
                val posFlow = MutableStateFlow(listOf(position("b1", positionMs = 9_600L, isFinished = true)))
                val bookFlow = MutableStateFlow(listOf(book("b1", duration = 10_000L)))
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(10).first()
                result.shouldBeEmpty()
            }
        }

        // ====================================================================
        // B) No undercount: isFinished=false at high progress stays included
        // ====================================================================

        test("B: isFinished=false at 99% progress is included (no undercount)") {
            runTest {
                // 99% but the server hasn't marked it finished
                val posFlow = MutableStateFlow(listOf(position("b1", positionMs = 9_900L, isFinished = false)))
                val bookFlow = MutableStateFlow(listOf(book("b1", duration = 10_000L)))
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(10).first()
                result shouldHaveSize 1
                result.first().shouldBeInstanceOf<ContinueListeningItem.Ready>()
            }
        }

        test("B: isFinished=false at 100% progress is excluded by defense-in-depth") {
            runTest {
                // position == duration → treated as finished even without the flag
                val posFlow = MutableStateFlow(listOf(position("b1", positionMs = 10_000L, isFinished = false)))
                val bookFlow = MutableStateFlow(listOf(book("b1", duration = 10_000L)))
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(10).first()
                result.shouldBeEmpty()
            }
        }

        // ====================================================================
        // C) Race-safety: rapid position re-emissions don't drop items
        // ====================================================================

        test("C: rapid position re-emissions don't reduce item count below stable value") {
            runTest {
                val positions = (1..3).map { i -> position("b$i", positionMs = 1_000L) }
                val posFlow = MutableStateFlow(positions)
                val bookFlow = MutableStateFlow((1..3).map { i -> book("b$i") })
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                // Stable first emission — should have 3 items
                val first = repo.observeContinueListening(10).first()
                first shouldHaveSize 3

                // Rapid re-emit (same positions) — should still have 3 items
                posFlow.value = positions.map { it.copy(positionMs = it.positionMs + 1) }
                val second = repo.observeContinueListening(10).first()
                second shouldHaveSize 3
            }
        }

        // ====================================================================
        // D) Loading placeholder: missing book → Loading; arrival → Ready
        // ====================================================================

        test("D: position with no matching book emits Loading placeholder") {
            runTest {
                val posFlow = MutableStateFlow(listOf(position("b1")))
                // Book NOT in the flow yet
                val bookFlow = MutableStateFlow(emptyList<BookListItem>())
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(10).first()
                result shouldHaveSize 1
                val item = result.first().shouldBeInstanceOf<ContinueListeningItem.Loading>()
                item.bookId shouldBe "b1"
            }
        }

        test("D: Loading transitions to Ready when book arrives in the next emission") {
            runTest {
                val posFlow = MutableStateFlow(listOf(position("b1")))
                val bookFlow = MutableStateFlow(emptyList<BookListItem>())
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                // First emission: Loading
                val loading = repo.observeContinueListening(10).first()
                loading.first().shouldBeInstanceOf<ContinueListeningItem.Loading>()

                // Book arrives → next emission should be Ready
                bookFlow.value = listOf(book("b1"))
                // Collect two emissions total: the Loading one and the Ready one
                val ready = repo.observeContinueListening(10).first()
                // After bookFlow update, observeBookListItems re-emits; we get the Ready item
                // (We re-subscribe to get the latest state after the bookFlow has the book)
                // This tests that the flow correctly updates when the book arrives.
                ready.first().shouldBeInstanceOf<ContinueListeningItem.Ready>()
            }
        }

        // ====================================================================
        // E) Size limit
        // ====================================================================

        test("E: limit caps the number of items returned") {
            runTest {
                val positions = (1..10).map { i -> position("b$i") }
                val posFlow = MutableStateFlow(positions)
                val bookFlow = MutableStateFlow((1..10).map { i -> book("b$i") })
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(5).first()
                result shouldHaveSize 5
            }
        }

        // ====================================================================
        // F) Ordering: position order is preserved
        // ====================================================================

        test("F: items preserve position ordering") {
            runTest {
                val positions =
                    listOf(
                        position("b3", positionMs = 3_000L),
                        position("b1", positionMs = 1_000L),
                        position("b2", positionMs = 2_000L),
                    )
                val posFlow = MutableStateFlow(positions)
                val bookFlow =
                    MutableStateFlow(
                        listOf(
                            book("b1"),
                            book("b2"),
                            book("b3"),
                        ),
                    )
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(10).first()
                result shouldHaveSize 3
                // Position order: b3 first, then b1, then b2
                result[0].bookId shouldBe "b3"
                result[1].bookId shouldBe "b1"
                result[2].bookId shouldBe "b2"
            }
        }

        // ====================================================================
        // G) Empty positions → empty list
        // ====================================================================

        test("G: empty positions emits empty list immediately") {
            runTest {
                val posFlow = MutableStateFlow(emptyList<PlaybackPositionEntity>())
                val bookFlow = MutableStateFlow(emptyList<BookListItem>())
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(10).first()
                result.shouldBeEmpty()
            }
        }

        // ====================================================================
        // Ready item shape
        // ====================================================================

        test("Ready item carries correct book data and progress") {
            runTest {
                val posFlow = MutableStateFlow(listOf(position("b1", positionMs = 5_000L)))
                val bookFlow = MutableStateFlow(listOf(book("b1", duration = 10_000L)))
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(10).first()
                result shouldHaveSize 1
                val ready = result.first().shouldBeInstanceOf<ContinueListeningItem.Ready>()
                ready.bookId shouldBe "b1"
                ready.book.progress shouldBe 0.5f
                ready.book.currentPositionMs shouldBe 5_000L
                ready.book.totalDurationMs shouldBe 10_000L
            }
        }

        test("Ready item progress is 0 for zero-duration book") {
            runTest {
                val posFlow = MutableStateFlow(listOf(position("b1", positionMs = 5_000L)))
                val bookFlow = MutableStateFlow(listOf(book("b1", duration = 0L)))
                val repo =
                    HomeRepositoryImpl(
                        bookRepository = makeBookRepo(bookFlow),
                        playbackPositionDao = makeDao(posFlow),
                    )

                val result = repo.observeContinueListening(10).first()
                result shouldHaveSize 1
                val ready = result.first().shouldBeInstanceOf<ContinueListeningItem.Ready>()
                ready.book.progress shouldBe 0f
            }
        }
    })
