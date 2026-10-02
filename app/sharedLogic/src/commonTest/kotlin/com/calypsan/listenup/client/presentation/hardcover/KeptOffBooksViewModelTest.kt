package com.calypsan.listenup.client.presentation.hardcover

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.turbineScope
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.presentation.settings.FakeHardcoverRepository
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.calls
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val CONNECTED = HardcoverConnection.Connected("reader", 1_700_000_000_000L, keptOffBookCount = 2)

private val TITLES = mapOf("b1" to "Zeta", "b2" to "alpha", "b3" to "Mistborn")

/** The library side: each id is a book titled from [TITLES]. A Mokkery mock, as in the settings tests: one member of twenty. */
private fun books(): BookRepository =
    mock<BookRepository>().also { repo ->
        every { repo.observeBookListItems(any<List<String>>()) } calls { (ids: List<String>) ->
            flowOf(ids.map { TestData.bookListItem(id = it, title = TITLES.getValue(it), authorName = "Author $it") })
        }
    }

private fun repoWith(vararg ids: String) =
    FakeHardcoverRepository(CONNECTED).apply { keptOffBooksResult = AppResult.Success(ids.map(::BookId)) }

private suspend fun ReceiveTurbine<KeptOffBooksUiState>.awaitLoaded(): KeptOffBooksUiState.Loaded {
    var item = awaitItem()
    while (item !is KeptOffBooksUiState.Loaded) item = awaitItem()
    return item
}

/** #1541's list: the books kept off Hardcover, by title, each with Sync again — optimistic and honest. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeptOffBooksViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("Loading until the server answers, then the kept-off books by title, named from the library") {
            runTest {
                val vm = KeptOffBooksViewModel(repoWith("b1", "b2"), books())
                vm.uiState.test {
                    awaitItem() shouldBe KeptOffBooksUiState.Loading
                    val loaded = awaitLoaded()
                    loaded.books.map { it.bookId } shouldBe listOf("b2", "b1")
                    loaded.books.first().title shouldBe "alpha"
                    loaded.books.first().authorNames shouldBe "Author b2"
                }
            }
        }

        test("a failed read is Unavailable, never an empty list") {
            runTest {
                val repo = FakeHardcoverRepository(CONNECTED).apply { keptOffBooksResult = AppResult.Failure(HardcoverError.Unavailable()) }
                val vm = KeptOffBooksViewModel(repo, books())
                vm.uiState.test {
                    advanceUntilIdle()
                    expectMostRecentItem() shouldBe KeptOffBooksUiState.Unavailable
                }
            }
        }

        test("the list is read again when the count moves, or when this device keeps a book off or syncs one again") {
            runTest {
                val repo = repoWith("b1")
                val vm = KeptOffBooksViewModel(repo, books())
                vm.uiState.test {
                    advanceUntilIdle()
                    repo.keptOffBooksCalls shouldBe 1
                    repo.connection.value = CONNECTED.copy(keptOffBookCount = 3)
                    advanceUntilIdle()
                    repo.keptOffBooksCalls shouldBe 2
                    repo.matchChangesFlow.tryEmit(BookId("b3"))
                    advanceUntilIdle()
                    repo.keptOffBooksCalls shouldBe 3
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("Sync again: the book leaves at once, the server is asked, and the screen says so") {
            runTest {
                val repo = repoWith("b1", "b2")
                val gate = CompletableDeferred<Unit>().also { repo.setBookSyncedGate = it }
                val vm = KeptOffBooksViewModel(repo, books())
                turbineScope {
                    val states = vm.uiState.testIn(backgroundScope)
                    val events = vm.events.testIn(backgroundScope)
                    advanceUntilIdle()

                    vm.syncAgain("b1")
                    runCurrent()
                    states
                        .expectMostRecentItem()
                        .shouldBeInstanceOf<KeptOffBooksUiState.Loaded>()
                        .books
                        .map { it.bookId } shouldBe
                        listOf("b2")
                    repo.syncedChoices shouldBe listOf(BookId("b1") to true)

                    repo.keptOffBooksResult = AppResult.Success(listOf(BookId("b2")))
                    gate.complete(Unit)
                    advanceUntilIdle()
                    events.awaitItem() shouldBe KeptOffBooksEvent.SyncingAgain(title = "Zeta", wasLast = false)
                    // The server's list now equals what is shown, so nothing new is emitted: b1 never comes back.
                    states.expectNoEvents()
                    vm.uiState.value
                        .shouldBeInstanceOf<KeptOffBooksUiState.Loaded>()
                        .books
                        .map { it.bookId } shouldBe listOf("b2")
                }
            }
        }

        test("syncing the last one again says so, so the screen can close") {
            runTest {
                val vm = KeptOffBooksViewModel(repoWith("b1"), books())
                turbineScope {
                    val states = vm.uiState.testIn(backgroundScope)
                    val events = vm.events.testIn(backgroundScope)
                    advanceUntilIdle()
                    vm.syncAgain("b1")
                    advanceUntilIdle()
                    events.awaitItem() shouldBe KeptOffBooksEvent.SyncingAgain(title = "Zeta", wasLast = true)
                    states.cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a refused Sync again puts the book back and shows why; a second press on one book asks once") {
            runTest {
                val repo = repoWith("b1", "b2").apply { setBookSyncedResult = AppResult.Failure(HardcoverError.Unavailable()) }
                val gate = CompletableDeferred<Unit>().also { repo.setBookSyncedGate = it }
                val vm = KeptOffBooksViewModel(repo, books())
                turbineScope {
                    val states = vm.uiState.testIn(backgroundScope)
                    val events = vm.events.testIn(backgroundScope)
                    advanceUntilIdle()
                    vm.syncAgain("b1")
                    vm.syncAgain("b1")
                    gate.complete(Unit)
                    advanceUntilIdle()

                    repo.syncedChoices shouldBe listOf(BookId("b1") to true)
                    events.awaitItem() shouldBe KeptOffBooksEvent.ShowError(HardcoverError.Unavailable())
                    states
                        .expectMostRecentItem()
                        .shouldBeInstanceOf<KeptOffBooksUiState.Loaded>()
                        .books
                        .map { it.bookId } shouldBe
                        listOf("b2", "b1")
                }
            }
        }
    })
