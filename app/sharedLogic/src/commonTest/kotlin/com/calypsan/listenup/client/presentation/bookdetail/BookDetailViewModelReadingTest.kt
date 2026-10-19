package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.Series
import com.calypsan.listenup.client.domain.model.SeriesWithBooks
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.SeriesId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Book Detail while the page is open and the reader's place in the book moves: the saved position and the chapters
 * are live, the action follows them at once, and the section order holds still until the next visit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelReadingTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun BookDetailViewModel.ready(): BookDetailUiState.Ready = state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()

        // The fixture's book is 90 min; six 15-min chapters.
        fun BookDetailFixture.withChapters() = apply { chapters.value = TestData.chapters(count = 6, chapterDuration = 900_000L) }

        test("progress, time left and the current chapter follow the saved position while the page is open") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.ready().currentChapter?.number shouldBe 2

                f.position.value = positionOf(2_800_000L) // playback saved a new position
                advanceUntilIdle()

                vm.ready().currentChapter?.number shouldBe 4
                vm.ready().timeRemainingMs shouldBe 5_400_000L - 2_800_000L
                vm.ready().chapterPreview.map { it.id } shouldBe listOf("chapter-2", "chapter-3", "chapter-4", "chapter-5", "chapter-6")
            }
        }

        test("the current chapter follows whether this book is the player's loaded book, live") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L)
                f.loadedBookId.value = BookId("other-book")
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.ready().currentChapter?.isInPlayer shouldBe false

                f.loadedBookId.value = BookId("book-1") // a chapter tap loads this book
                advanceUntilIdle()

                vm.ready().currentChapter?.isInPlayer shouldBe true
            }
        }

        test("the book finishing during the visit flips the action live and leaves the order alone") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                val order = vm.ready().sectionOrder
                order.first() shouldBe BookDetailSection.Chapters

                f.position.value = positionOf(5_400_000L, finished = true)
                advanceUntilIdle()

                vm.ready().primaryAction shouldBe PrimaryAction.ListenAgain
                vm.ready().readingStage shouldBe ReadingStage.Finished
                vm.ready().sectionOrder shouldBe order
            }
        }

        test("leaving for another book and coming back is a new visit, ordered by where you are now") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                f.position.value = positionOf(5_400_000L, finished = true)
                advanceUntilIdle()

                vm.loadBook("book-2")
                advanceUntilIdle()
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.ready().sectionOrder.first() shouldBe BookDetailSection.Ratings
            }
        }

        test("asking for the same book again keeps the order (an iOS observer reusing its ViewModel)") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                f.position.value = positionOf(5_400_000L, finished = true)
                advanceUntilIdle()

                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.ready().sectionOrder.first() shouldBe BookDetailSection.Chapters
            }
        }

        test("a position tick never closes an open picker") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.showShelfPicker()
                advanceUntilIdle()

                repeat(3) { tick ->
                    f.position.value = positionOf(1_000_000L + (tick + 1) * 10_000L)
                    advanceUntilIdle()
                }

                vm.ready().showShelfPicker shouldBe true
            }
        }

        test("the chapter editor's saves reach the page: chapters are live") {
            runTest {
                val f = BookDetailFixture().withChapters()
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.ready().chapters.size shouldBe 6

                f.chapters.value = TestData.chapters(count = 3, chapterDuration = 1_800_000L)
                advanceUntilIdle()

                vm.ready().chapters.size shouldBe 3
            }
        }

        test("the series shelf comes from the book's first series, live") {
            runTest {
                val f = BookDetailFixture()
                f.detail("book-1").value =
                    TestData.bookDetail(id = "book-1", seriesId = "s1", seriesName = "Mistborn", seriesSequence = 1.0)
                f.series["s1"] =
                    MutableStateFlow(
                        SeriesWithBooks(
                            series = Series(id = SeriesId("s1"), name = "Mistborn"),
                            books = listOf(TestData.bookListItem(id = "book-1"), TestData.bookListItem(id = "book-2")),
                            bookSequences = mapOf("book-1" to 1.0, "book-2" to 2.0),
                        ),
                    )
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.ready().seriesShelf?.nextBookId shouldBe "book-2"
            }
        }

        test("a book in no series has no shelf and no Series section") {
            runTest {
                val f = BookDetailFixture().withChapters()
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.ready().seriesShelf shouldBe null
                vm.ready().sectionOrder.contains(BookDetailSection.Series) shouldBe false
            }
        }

        test("a member never sees the scan warning; an admin does") {
            runTest {
                val f = BookDetailFixture()
                f.detail("book-1").value = TestData.bookDetail(id = "book-1").copy(hasScanWarning = true)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.ready().showScanWarning shouldBe false

                f.adminStatus.value = true
                advanceUntilIdle()
                vm.ready().showScanWarning shouldBe true
            }
        }
    })
