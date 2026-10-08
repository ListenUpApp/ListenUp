package com.calypsan.listenup.client.presentation.hardcover

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.presentation.settings.FakeHardcoverRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val BOOK = "b1"
private val CONNECTED = HardcoverConnection.Connected("reader", 1L)
private val LINKED =
    HardcoverBookMatch.Linked(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir"), 2021, true, HardcoverBookSync.WAITING)

private val NOW = Instant.fromEpochMilliseconds(1_000_000L)

/** A [Clock] stopped at [NOW]. */
private object StoppedClock : Clock {
    override fun now(): Instant = NOW
}

private fun TestScope.bookViewModel(
    repo: FakeHardcoverRepository,
    errorBus: ErrorBus = ErrorBus(),
) = BookHardcoverViewModel(BOOK, repo, errorBus, StoppedClock)

/** Spec B5's Book Detail row: hidden unless connected, live as sync moves; with #1541's Sync with Hardcover switch. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookHardcoverViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("not connected, or broken, shows nothing and asks nothing") {
            runTest {
                listOf(
                    HardcoverConnection.NotConnected(),
                    HardcoverConnection.Broken(HardcoverBrokenReason.REVOKED),
                ).forEach { connection ->
                    val repo = FakeHardcoverRepository(connection)
                    val vm = bookViewModel(repo)
                    vm.uiState.test {
                        advanceUntilIdle()
                        expectMostRecentItem() shouldBe BookHardcoverUiState.Hidden
                    }
                    repo.bookMatchCalls shouldBe 0
                }
            }
        }

        test("each answer maps to its row; a never-matched book is the switch alone, and a failure shows nothing") {
            runTest {
                listOf(
                    AppResult.Success(HardcoverBookMatch.Unmatched) to BookHardcoverUiState.Unmatched,
                    AppResult.Success(HardcoverBookMatch.NeedsMatch) to BookHardcoverUiState.NeedsMatch,
                    AppResult.Success(LINKED) to
                        BookHardcoverUiState.Linked(
                            HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, true, 9_001L),
                            HardcoverBookSync.WAITING,
                        ),
                    AppResult.Failure(HardcoverError.Unavailable()) to BookHardcoverUiState.Hidden,
                ).forEach { (answer, expected) ->
                    val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = answer }
                    val vm = bookViewModel(repo)
                    vm.uiState.test {
                        advanceUntilIdle()
                        expectMostRecentItem() shouldBe expected
                    }
                }
            }
        }

        test("a sync, or a change to this book's match, re-reads the row; another book's change doesn't") {
            runTest {
                val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = AppResult.Success(LINKED) }
                val vm = bookViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    repo.bookMatchCalls shouldBe 1
                    repo.connection.value = CONNECTED.copy(lastSyncedAt = 5L)
                    advanceUntilIdle()
                    repo.bookMatchCalls shouldBe 2
                    repo.matchChangesFlow.tryEmit(BookId("other"))
                    advanceUntilIdle()
                    repo.bookMatchCalls shouldBe 2
                    repo.matchChangesFlow.tryEmit(BookId(BOOK))
                    advanceUntilIdle()
                    repo.bookMatchCalls shouldBe 3
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a book this device matched under a minute ago reads Matched just now, until the minute is up") {
            runTest {
                val repo =
                    FakeHardcoverRepository(CONNECTED).apply {
                        bookMatchResult = AppResult.Success(LINKED)
                        linkedAtByBook[BookId(BOOK)] = NOW - 20.seconds
                    }
                val vm = bookViewModel(repo)
                vm.uiState.test {
                    runCurrent()
                    (expectMostRecentItem() as BookHardcoverUiState.Linked).justMatched shouldBe true
                    advanceTimeBy(41.seconds)
                    (expectMostRecentItem() as BookHardcoverUiState.Linked).justMatched shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a match made long ago, or on another device, never reads just now") {
            runTest {
                listOf(NOW - 61.seconds, null).forEach { linkedAt ->
                    val repo =
                        FakeHardcoverRepository(CONNECTED).apply {
                            bookMatchResult = AppResult.Success(LINKED)
                            linkedAt?.let { linkedAtByBook[BookId(BOOK)] = it }
                        }
                    val vm = bookViewModel(repo)
                    vm.uiState.test {
                        advanceUntilIdle()
                        (expectMostRecentItem() as BookHardcoverUiState.Linked).justMatched shouldBe false
                    }
                }
            }
        }

        test("Remove match unlinks the book; a refusal goes to the error bus") {
            runTest {
                val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = AppResult.Success(LINKED) }
                val errorBus = ErrorBus()
                val vm = bookViewModel(repo, errorBus)
                errorBus.errors.test {
                    vm.uiState.test {
                        advanceUntilIdle()
                        vm.removeMatch()
                        advanceUntilIdle()
                        repo.unlinks shouldBe listOf(BookId(BOOK))
                        repo.unlinkResult = AppResult.Failure(HardcoverError.Unavailable())
                        vm.removeMatch()
                        advanceUntilIdle()
                        cancelAndIgnoreRemainingEvents()
                    }
                    awaitItem() shouldBe HardcoverError.Unavailable()
                }
            }
        }

        test("connected, a book never matched still has a row: just the switch, on (decision 1)") {
            runTest {
                val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = AppResult.Success(HardcoverBookMatch.Unmatched) }
                bookViewModel(repo).uiState.test {
                    advanceUntilIdle()
                    expectMostRecentItem() shouldBe BookHardcoverUiState.Unmatched
                }
            }
        }

        test("switching off a book never matched keeps it off at once, with nothing to ask about (decision 1)") {
            runTest {
                val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = AppResult.Success(HardcoverBookMatch.Unmatched) }
                val gate = CompletableDeferred<Unit>().also { repo.setBookSyncedGate = it }
                val vm = bookViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.setSynced(false)
                    runCurrent()
                    expectMostRecentItem() shouldBe BookHardcoverUiState.KeptOff()
                    repo.syncedChoices shouldBe listOf(BookId(BOOK) to false)
                    gate.complete(Unit)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a kept-off book is KeptOff") {
            runTest {
                val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = AppResult.Success(HardcoverBookMatch.KeptOff) }
                bookViewModel(repo).uiState.test {
                    advanceUntilIdle()
                    expectMostRecentItem() shouldBe BookHardcoverUiState.KeptOff()
                }
            }
        }

        test("a linked book says what keeping it off would remove — and only then is the listener asked first") {
            runTest {
                mapOf(
                    LINKED to null,
                    LINKED.copy(readsInReaders = true) to KeepOffRemoves.READS,
                    LINKED.copy(onToReadFromHardcover = true) to KeepOffRemoves.TO_READ,
                    LINKED.copy(readsInReaders = true, onToReadFromHardcover = true) to KeepOffRemoves.READS_AND_TO_READ,
                ).forEach { (match, removes) ->
                    val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = AppResult.Success(match) }
                    bookViewModel(repo).uiState.test {
                        advanceUntilIdle()
                        expectMostRecentItem().shouldBeInstanceOf<BookHardcoverUiState.Linked>().keepOffRemoves shouldBe removes
                    }
                }
            }
        }

        test("switching it off shows it kept off at once, and the server's answer holds it there") {
            runTest {
                val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = AppResult.Success(LINKED) }
                val gate = CompletableDeferred<Unit>().also { repo.setBookSyncedGate = it }
                val vm = bookViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.setSynced(false)
                    runCurrent()
                    expectMostRecentItem() shouldBe BookHardcoverUiState.KeptOff()
                    repo.syncedChoices shouldBe listOf(BookId(BOOK) to false)

                    repo.bookMatchResult = AppResult.Success(HardcoverBookMatch.KeptOff)
                    gate.complete(Unit)
                    advanceUntilIdle()
                    // The server's KeptOff equals what is shown, so nothing new is emitted: no flicker back.
                    expectNoEvents()
                    vm.uiState.value shouldBe BookHardcoverUiState.KeptOff()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a refused switch goes back to what the server holds, and the refusal reaches the error bus") {
            runTest {
                val repo =
                    FakeHardcoverRepository(CONNECTED).apply {
                        bookMatchResult = AppResult.Success(LINKED)
                        setBookSyncedResult = AppResult.Failure(HardcoverError.Unavailable())
                    }
                val errorBus = ErrorBus()
                val vm = bookViewModel(repo, errorBus)
                errorBus.errors.test {
                    vm.uiState.test {
                        advanceUntilIdle()
                        vm.setSynced(false)
                        advanceUntilIdle()
                        expectMostRecentItem().shouldBeInstanceOf<BookHardcoverUiState.Linked>()
                        cancelAndIgnoreRemainingEvents()
                    }
                    awaitItem() shouldBe HardcoverError.Unavailable()
                }
            }
        }

        test("switching it back on reads on at once, then shows the row the server answers") {
            runTest {
                val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = AppResult.Success(HardcoverBookMatch.KeptOff) }
                val gate = CompletableDeferred<Unit>().also { repo.setBookSyncedGate = it }
                val vm = bookViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.setSynced(true)
                    runCurrent()
                    expectMostRecentItem() shouldBe BookHardcoverUiState.KeptOff(isResuming = true)

                    repo.bookMatchResult = AppResult.Success(LINKED)
                    gate.complete(Unit)
                    advanceUntilIdle()
                    expectMostRecentItem().shouldBeInstanceOf<BookHardcoverUiState.Linked>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the switch is ignored when it already says so, while a flip saves, and with no row") {
            runTest {
                val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = AppResult.Success(LINKED) }
                val gate = CompletableDeferred<Unit>().also { repo.setBookSyncedGate = it }
                val vm = bookViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.setSynced(true)
                    vm.setSynced(false)
                    runCurrent()
                    vm.setSynced(false)
                    vm.setSynced(true)
                    runCurrent()
                    repo.syncedChoices shouldBe listOf(BookId(BOOK) to false)
                    gate.complete(Unit)
                    cancelAndIgnoreRemainingEvents()
                }

                val notConnected = FakeHardcoverRepository(HardcoverConnection.NotConnected())
                val hidden = bookViewModel(notConnected)
                hidden.uiState.test {
                    advanceUntilIdle()
                    hidden.setSynced(false)
                    advanceUntilIdle()
                    cancelAndIgnoreRemainingEvents()
                }
                notConnected.syncedChoices shouldBe emptyList()
            }
        }
    })
