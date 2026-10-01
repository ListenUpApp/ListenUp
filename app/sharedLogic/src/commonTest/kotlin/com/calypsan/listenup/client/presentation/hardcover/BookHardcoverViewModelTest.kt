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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val BOOK = "b1"
private val CONNECTED = HardcoverConnection.Connected("reader", 1L)
private val LINKED =
    HardcoverBookMatch.Linked(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir"), 2021, true, HardcoverBookSync.WAITING)

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
                    val vm = BookHardcoverViewModel(BOOK, repo)
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
                            HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, true),
                            HardcoverBookSync.WAITING,
                        ),
                    AppResult.Failure(HardcoverError.Unavailable()) to BookHardcoverUiState.Hidden,
                ).forEach { (answer, expected) ->
                    val repo = FakeHardcoverRepository(CONNECTED).apply { bookMatchResult = answer }
                    val vm = BookHardcoverViewModel(BOOK, repo)
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
                val vm = BookHardcoverViewModel(BOOK, repo)
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
    })
