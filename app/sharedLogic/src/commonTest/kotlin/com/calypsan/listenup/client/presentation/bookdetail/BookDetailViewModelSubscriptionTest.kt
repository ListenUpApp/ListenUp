package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.core.BookId
import dev.mokkery.matcher.any
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.CoroutineContext

/**
 * The main thread as a queue of tasks a test steps through one at a time, so it can put a reader's tap between
 * any two of them — where real input lands.
 */
private class MainQueue : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        tasks.addLast(block)
    }

    /** Runs every queued task, and whatever they queue, calling [between] after each one. */
    fun drain(between: () -> Unit = {}) {
        while (tasks.isNotEmpty()) {
            tasks.removeFirst().run()
            between()
        }
    }
}

/**
 * What the canonical shape buys: Book Detail holds Room open only while a screen watches it (plus the five-second
 * grace a rotation needs), and a page can only ever describe the book that was asked for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelSubscriptionTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("nothing reads Room until a screen watches, and Room is let go five seconds after the last one leaves") {
            runTest {
                val f = BookDetailFixture()
                val vm = f.build()
                vm.loadBook("book-1")
                advanceUntilIdle()
                f.liveDetailObservers shouldBe 0

                val screen = backgroundScope.launch { vm.state.collect {} }
                runCurrent() // advanceUntilIdle alone never starts a background-only task
                advanceUntilIdle()
                f.liveDetailObservers shouldBe 1
                vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()

                screen.cancel()
                advanceTimeBy(4_999)
                runCurrent()
                f.liveDetailObservers shouldBe 1 // a rotation comes back inside this window
                advanceTimeBy(2)
                runCurrent()
                f.liveDetailObservers shouldBe 0
            }
        }

        test("a shelf add that fails after you moved on leaves the next book alone") {
            runTest {
                val f = BookDetailFixture()
                val gate = CompletableDeferred<AppResult<Int>>()
                f.addToShelfGate = gate
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.addBookToShelf("shelf-1")
                advanceUntilIdle()
                vm.loadBook("book-2")
                advanceUntilIdle()
                gate.complete(AppResult.Failure(ValidationError(message = "That shelf is gone.")))
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.book.id.value shouldBe "book-2"
                ready.shelfError shouldBe null
                ready.isAddingToShelf shouldBe false
            }
        }

        test("a late shelf failure from your first visit to a book stays off your next visit to it") {
            runTest {
                val f = BookDetailFixture()
                val gate = CompletableDeferred<AppResult<Int>>()
                f.addToShelfGate = gate
                val vm = watch(f.build())
                vm.loadBook("book-1")
                advanceUntilIdle()

                vm.addBookToShelf("shelf-1")
                advanceUntilIdle()
                vm.loadBook("book-2")
                advanceUntilIdle()
                vm.loadBook("book-1")
                advanceUntilIdle()
                gate.complete(AppResult.Failure(ValidationError(message = "That shelf is gone.")))
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.book.id.value shouldBe "book-1"
                ready.shelfError shouldBe null
                ready.isAddingToShelf shouldBe false
            }
        }

        test("Room re-homing a stranded book never lets a tap send Show to all members twice") {
            runTest {
                val main = MainQueue()
                Dispatchers.setMain(main)
                val f = BookDetailFixture()
                f.adminStatus.value = true
                f.visibility.setVisibility(BookId("book-1"), BookVisibility.Stranded)
                val vm = f.build()
                backgroundScope.launch(main) { vm.state.collect {} }
                vm.loadBook("book-1")
                main.drain()

                vm.restoreToAllBooks()
                main.drain()
                f.visibility.setVisibility(BookId("book-1"), BookVisibility.Public)
                // The reader keeps tapping "Show to all members", between every two main-thread tasks.
                main.drain(between = vm::restoreToAllBooks)

                vm.state.value
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .visibility shouldBe BookVisibility.Public
                verifySuspend(VerifyMode.exactly(1)) { f.bookEditRepository.setBookCollections(any(), any()) }
            }
        }
    })
