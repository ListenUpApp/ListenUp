package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.playback.BookPlayhead
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
                // Once loaded, the player starts from the saved place.
                f.playhead.value = BookPlayhead(BookId("book-1"), 1_000_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.ready().currentChapter?.isInPlayer shouldBe false

                f.loadedBookId.value = BookId("book-1") // a chapter tap loads this book
                advanceUntilIdle()

                vm.ready().currentChapter?.isInPlayer shouldBe true
            }
        }

        test("for the player's book, the live playhead crossing a chapter moves the current chapter at once") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L) // saved: chapter 2
                f.loadedBookId.value = BookId("book-1")
                f.playhead.value = BookPlayhead(BookId("book-1"), 1_000_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.ready().currentChapter?.number shouldBe 2

                // The player crossed into chapter 3; the saved row has not caught up.
                f.playhead.value = BookPlayhead(BookId("book-1"), 1_805_000L)
                advanceUntilIdle()

                vm.ready().currentChapter?.number shouldBe 3
                vm.ready().currentChapter?.elapsedInChapterMs shouldBe 5_000L
                vm.ready().timeRemainingMs shouldBe 5_400_000L - 1_805_000L
            }
        }

        test("a book that is not in the player ignores the playhead") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L)
                f.loadedBookId.value = BookId("other-book")
                f.playhead.value = BookPlayhead(BookId("other-book"), 2_800_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.ready().currentChapter?.number shouldBe 2
                vm.ready().timeRemainingMs shouldBe 5_400_000L - 1_000_000L
            }
        }

        test("a playhead still holding the last book's position never lands on the newly loaded book's page") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L) // this book's saved place: chapter 2
                f.loadedBookId.value = BookId("book-1") // the player has just switched to this book…
                // …its playhead is still the last one's.
                f.playhead.value = BookPlayhead(BookId("other-book"), 2_800_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.ready().currentChapter?.number shouldBe 2
                vm.ready().timeRemainingMs shouldBe 5_400_000L - 1_000_000L

                f.playhead.value = BookPlayhead(BookId("book-1"), 1_805_000L) // its own position arrives
                advanceUntilIdle()
                vm.ready().currentChapter?.number shouldBe 3
            }
        }

        test("playhead ticks inside the same minute and chapter do not re-emit the page, and keep a picker open") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.position.value = positionOf(1_000_000L)
                f.loadedBookId.value = BookId("book-1")
                f.playhead.value = BookPlayhead(BookId("book-1"), 1_000_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.showShelfPicker()
                advanceUntilIdle()
                val before = vm.state.value

                f.playhead.value = BookPlayhead(BookId("book-1"), 1_000_250L)
                advanceUntilIdle()
                f.playhead.value = BookPlayhead(BookId("book-1"), 1_010_000L)
                advanceUntilIdle()
                (vm.state.value === before) shouldBe true

                // A minute on: what the page shows changes.
                f.playhead.value = BookPlayhead(BookId("book-1"), 1_080_000L)
                advanceUntilIdle()
                (vm.state.value === before) shouldBe false
                vm.ready().showShelfPicker shouldBe true
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

        test("a book in a series has no series shelf: the path line under the cover is the way there") {
            runTest {
                val f = BookDetailFixture().withChapters()
                f.detail("book-1").value =
                    TestData.bookDetail(id = "book-1", seriesId = "s1", seriesName = "Mistborn", seriesSequence = 1.0)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.ready().sectionOrder shouldBe
                    listOf(
                        BookDetailSection.About,
                        BookDetailSection.Ratings,
                        BookDetailSection.Hardcover,
                        BookDetailSection.Readers,
                        BookDetailSection.Chapters,
                        BookDetailSection.Details,
                    )
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
