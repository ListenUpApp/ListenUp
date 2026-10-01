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

/** Spec B5's Book Detail row: hidden unless connected and matched (or needing a match), live as sync moves. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookHardcoverViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("not connected, or broken, shows nothing and asks nothing") {
            runTest {
                listOf(HardcoverConnection.NotConnected(), HardcoverConnection.Broken(HardcoverBrokenReason.REVOKED)).forEach {
                    val repo = FakeHardcoverRepository(it)
                    val vm = bookViewModel(repo)
                    vm.uiState.test {
                        advanceUntilIdle()
                        expectMostRecentItem() shouldBe BookHardcoverUiState.Hidden
                    }
                    repo.bookMatchCalls shouldBe 0
                }
            }
        }

        test("each answer maps to its row; a never-matched book and a failure show nothing") {
            runTest {
                listOf(
                    AppResult.Success(HardcoverBookMatch.Unmatched) to BookHardcoverUiState.Hidden,
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
    })
