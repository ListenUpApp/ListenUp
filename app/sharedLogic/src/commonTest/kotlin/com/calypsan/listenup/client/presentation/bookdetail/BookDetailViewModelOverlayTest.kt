package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.Event
import app.cash.turbine.test
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * What the reader sees of their own actions while Room keeps talking: the pickers, busy flags and inline errors
 * that must survive a rebuild, the ones a book switch must clear, and the account-wide facts every Ready carries.
 * Written against the ViewModel as it was before PR 0 and kept unchanged through it — the proof that converting
 * to `stateIn(WhileSubscribed)` changed no behaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelOverlayTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun BookDetailViewModel.ready(): BookDetailUiState.Ready = state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()

        fun positionAt(ms: Long) =
            PlaybackPosition(
                bookId = "book-1",
                positionMs = ms,
                playbackSpeed = 1f,
                hasCustomSpeed = false,
                volumeBoostDb = 0f,
                hasCustomBoost = false,
                measuredGainDb = null,
                updatedAtMs = 0L,
                syncedAtMs = null,
                lastPlayedAtMs = 0L,
            )

        test("an open shelf picker stays open when Room rebuilds the book") {
            runTest {
                val f = BookDetailFixture()
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.showShelfPicker()
                advanceUntilIdle()
                f.availability.update { it.copy(isWaitingForWifi = true) } // a download tick rebuilds Ready
                advanceUntilIdle()

                vm.ready().isWaitingForWifi shouldBe true
                vm.ready().showShelfPicker shouldBe true
            }
        }

        test("an open collection picker and its error survive the held set re-emitting") {
            runTest {
                val f = BookDetailFixture()
                everySuspend { f.collectionRepository.addBook(any(), any()) } returns
                    AppResult.Failure(ValidationError(message = "No room."))
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.showCollectionPicker()
                vm.addBookToCollection("col-1")
                advanceUntilIdle()
                f.inbox.hold("another-book")
                advanceUntilIdle()

                vm.ready().showCollectionPicker shouldBe true
                vm.ready().collectionError shouldBe "No room."
                vm.ready().isAddingToCollection shouldBe false
            }
        }

        test("a busy flag stays up through a rebuild until its write answers") {
            runTest {
                val f = BookDetailFixture()
                val gate = CompletableDeferred<AppResult<Unit>>()
                f.markCompleteGate = gate
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.markComplete()
                advanceUntilIdle()
                vm.ready().isMarkingComplete shouldBe true
                f.availability.update { it.copy(isWaitingForWifi = true) }
                advanceUntilIdle()
                vm.ready().isMarkingComplete shouldBe true

                gate.complete(AppResult.Success(Unit))
                advanceUntilIdle()
                vm.ready().isMarkingComplete shouldBe false
                vm.ready().isComplete shouldBe true
            }
        }

        test("a delete refusal survives a rebuild, and clearing it clears it") {
            runTest {
                val f = BookDetailFixture()
                f.deleteResult = AppResult.Failure(BookError.NotFound())
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.deleteBook()
                advanceUntilIdle()
                f.availability.update { it.copy(isWaitingForWifi = true) }
                advanceUntilIdle()
                vm.ready().deleteError shouldBe BookError.NotFound()
                vm.ready().isDeletingBook shouldBe false

                vm.clearDeleteError()
                advanceUntilIdle()
                vm.ready().deleteError shouldBe null
            }
        }

        test("a refused shelf add keeps the picker open with the reason, and clearing it clears it") {
            runTest {
                val f = BookDetailFixture()
                f.addToShelfResult = AppResult.Failure(ValidationError(message = "That shelf is full."))
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.showShelfPicker()
                vm.addBookToShelf("shelf-1")
                advanceUntilIdle()
                vm.ready().showShelfPicker shouldBe true
                vm.ready().shelfError shouldBe "That shelf is full."

                vm.clearShelfError()
                advanceUntilIdle()
                vm.ready().shelfError shouldBe null
            }
        }

        test("moving to another book closes everything that was open on the first") {
            runTest {
                val f = BookDetailFixture()
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.showShelfPicker()
                vm.showCollectionPicker()
                advanceUntilIdle()

                vm.loadBook("book-2")
                advanceUntilIdle()

                vm.ready().book.id.value shouldBe "book-2"
                vm.ready().showShelfPicker shouldBe false
                vm.ready().showCollectionPicker shouldBe false
            }
        }

        test("loading the same book again keeps what is open and emits nothing") {
            runTest {
                val f = BookDetailFixture()
                val vm = f.build()
                vm.state.test {
                    vm.loadBook("book-1")
                    advanceUntilIdle()
                    vm.showShelfPicker()
                    advanceUntilIdle()
                    expectMostRecentItem().shouldBeInstanceOf<BookDetailUiState.Ready>().showShelfPicker shouldBe true

                    vm.loadBook("book-1")
                    advanceUntilIdle()
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a picker opened before any book is on screen does not open") {
            runTest {
                val f = BookDetailFixture()
                val vm = watch(f.build())
                vm.showShelfPicker()
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.ready().showShelfPicker shouldBe false
            }
        }

        test("a book whose row vanishes shows NotFound, and comes back with nothing left open") {
            runTest {
                val f = BookDetailFixture()
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.showShelfPicker()
                advanceUntilIdle()

                f.detail("book-1").value = null
                advanceUntilIdle()
                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Error>()
                    .error
                    .shouldBeInstanceOf<BookError.NotFound>()

                f.detail("book-1").value = TestData.bookDetail(id = "book-1")
                advanceUntilIdle()
                vm.ready().showShelfPicker shouldBe false
            }
        }

        test("admin status known before the book loads is on its first Ready, and follows live") {
            runTest {
                val f = BookDetailFixture()
                f.isAdmin.value = true
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.ready().isAdmin shouldBe true

                f.isAdmin.value = false
                advanceUntilIdle()
                vm.ready().isAdmin shouldBe false
            }
        }

        test("every tag in the library reaches Ready, live") {
            runTest {
                val f = BookDetailFixture()
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.ready().allTags shouldBe emptyList()

                f.allTags.value = listOf(TestData.tag(id = "t1", slug = "cozy"))
                advanceUntilIdle()
                vm.ready().allTags.map { it.id } shouldBe listOf("t1")
            }
        }

        // PR 1 makes the position live, so the page will stay finished: invert this test then, on purpose.
        test("marking finished shows it at once; the next Room emission re-derives it from the position read at load") {
            runTest {
                val f = BookDetailFixture()
                f.position = positionAt(1_800_000L) // 30 min of the fixture's 90
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()
                vm.ready().isComplete shouldBe false

                vm.markComplete()
                advanceUntilIdle()
                vm.ready().isComplete shouldBe true

                f.availability.update { it.copy(isWaitingForWifi = true) }
                advanceUntilIdle()
                vm.ready().isComplete shouldBe false
                vm.ready().progress shouldBe (1_800_000f / 5_400_000f)
            }
        }

        test("discarding progress clears progress and time left at once") {
            runTest {
                val f = BookDetailFixture()
                f.position = positionAt(1_800_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.discardProgress()
                advanceUntilIdle()

                vm.ready().progress shouldBe null
                vm.ready().timeRemainingFormatted shouldBe null
                vm.ready().isComplete shouldBe false
                vm.ready().isDiscardingProgress shouldBe false
            }
        }

        test("restarting shows the book at the start at once") {
            runTest {
                val f = BookDetailFixture()
                f.position = positionAt(1_800_000L)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.restartBook()
                advanceUntilIdle()

                vm.ready().progress shouldBe 0f
                vm.ready().isComplete shouldBe false
                vm.ready().isRestarting shouldBe false
            }
        }

        test("once a restored book is re-homed, being stranded again does not bring 'restoring' back") {
            runTest {
                val f = BookDetailFixture()
                f.isAdmin.value = true
                f.visibility.setVisibility(BookId("book-1"), BookVisibility.Stranded)
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.restoreToAllBooks()
                advanceUntilIdle()
                vm.ready().isRestoringToAllBooks shouldBe true

                f.visibility.setVisibility(BookId("book-1"), BookVisibility.Public)
                advanceUntilIdle()
                f.visibility.setVisibility(BookId("book-1"), BookVisibility.Stranded)
                advanceUntilIdle()

                vm.ready().isRestoringToAllBooks shouldBe false
            }
        }

        test("coming back to the book shows it at once, with no Loading in between") {
            runTest {
                val f = BookDetailFixture()
                val vm = f.build()
                vm.loadBook("book-1")
                val firstVisit = backgroundScope.launch { vm.state.collect {} }
                advanceUntilIdle()
                vm.ready()
                firstVisit.cancel()
                advanceTimeBy(60_000)
                runCurrent()

                vm.state.test {
                    awaitItem().shouldBeInstanceOf<BookDetailUiState.Ready>()
                    advanceUntilIdle()
                    cancelAndConsumeRemainingEvents()
                        .filterIsInstance<Event.Item<BookDetailUiState>>()
                        .filter { it.value is BookDetailUiState.Loading }
                        .shouldBeEmpty()
                }
            }
        }
    })
