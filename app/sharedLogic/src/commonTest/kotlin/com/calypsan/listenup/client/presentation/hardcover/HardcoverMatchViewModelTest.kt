package com.calypsan.listenup.client.presentation.hardcover

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.presentation.settings.FakeHardcoverRepository
import com.calypsan.listenup.core.BookId
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private const val BOOK = "b1"
private val REAL = HardcoverBookCandidate(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir"), 2021, 8_107)
private val SUMMARY = HardcoverBookCandidate(1L, null, "Project Hail Mary (Summary)", listOf("Quick Reads"), 2022, 0)

private fun library(present: Boolean = true): BookRepository =
    mock<BookRepository>().also { repo ->
        everySuspend { repo.getBookListItem(any()) } returns
            if (present) TestData.bookListItem(id = BOOK, title = "Project Hail Mary", authorName = "Andy Weir") else null
    }

/** Find on Hardcover: search by title, rank by author, link the pick, or remove the match. */
@OptIn(ExperimentalCoroutinesApi::class)
class HardcoverMatchViewModelTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("opening searches for the title alone and ranks the author's book first") {
            runTest {
                val repo = FakeHardcoverRepository().apply { searchResult = AppResult.Success(listOf(SUMMARY, REAL)) }
                val vm = HardcoverMatchViewModel(BOOK, repo, library())
                vm.uiState.test {
                    advanceUntilIdle()
                    val ready = expectMostRecentItem().shouldBeInstanceOf<HardcoverMatchUiState.Ready>()
                    ready.query shouldBe "Project Hail Mary"
                    ready.bookAuthors shouldBe "Andy Weir"
                    ready.search.shouldBeInstanceOf<HardcoverSearchState.Results>().rows.map { it.hcBookId } shouldBe
                        listOf(427_578L, 1L)
                }
                repo.searches shouldBe listOf("Project Hail Mary")
            }
        }

        test("a book no longer in the library says so and searches nothing") {
            runTest {
                val repo = FakeHardcoverRepository()
                val vm = HardcoverMatchViewModel(BOOK, repo, library(present = false))
                vm.uiState.test {
                    advanceUntilIdle()
                    expectMostRecentItem() shouldBe HardcoverMatchUiState.BookMissing
                }
                repo.searches shouldBe emptyList()
            }
        }

        test("an edited query searches only when submitted, and a blank one not at all") {
            runTest {
                val repo = FakeHardcoverRepository().apply { searchResult = AppResult.Success(emptyList()) }
                val vm = HardcoverMatchViewModel(BOOK, repo, library())
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.onQueryChange("Weir")
                    advanceUntilIdle()
                    repo.searches shouldBe listOf("Project Hail Mary")
                    vm.search()
                    advanceUntilIdle()
                    repo.searches shouldBe listOf("Project Hail Mary", "Weir")
                    (expectMostRecentItem() as HardcoverMatchUiState.Ready).search shouldBe HardcoverSearchState.NoResults
                    vm.onQueryChange("   ")
                    vm.search()
                    advanceUntilIdle()
                    repo.searches.size shouldBe 2
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a failed search carries the typed error") {
            runTest {
                val repo = FakeHardcoverRepository().apply { searchResult = AppResult.Failure(HardcoverError.Unavailable()) }
                val vm = HardcoverMatchViewModel(BOOK, repo, library())
                vm.uiState.test {
                    advanceUntilIdle()
                    (expectMostRecentItem() as HardcoverMatchUiState.Ready).search shouldBe
                        HardcoverSearchState.Failed(HardcoverError.Unavailable())
                }
            }
        }

        test("picking a candidate links it with its edition, shows it in flight, and says Linked") {
            runTest {
                val repo =
                    FakeHardcoverRepository().apply {
                        searchResult = AppResult.Success(listOf(REAL))
                        linkGate = CompletableDeferred()
                    }
                val vm = HardcoverMatchViewModel(BOOK, repo, library())
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.link(427_578L)
                    advanceUntilIdle()
                    (expectMostRecentItem() as HardcoverMatchUiState.Ready).linkingId shouldBe 427_578L
                    vm.events.test {
                        repo.linkGate!!.complete(Unit)
                        awaitItem() shouldBe HardcoverMatchEvent.Linked
                    }
                    cancelAndIgnoreRemainingEvents()
                }
                repo.links shouldBe listOf(Triple(BookId(BOOK), 427_578L, 9_001L))
            }
        }

        test("a link the server refuses shows its error") {
            runTest {
                val repo =
                    FakeHardcoverRepository().apply {
                        searchResult = AppResult.Success(listOf(REAL))
                        linkResult = AppResult.Failure(HardcoverError.NotConnected())
                    }
                val vm = HardcoverMatchViewModel(BOOK, repo, library())
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.events.test {
                        vm.link(427_578L)
                        awaitItem() shouldBe HardcoverMatchEvent.ShowError(HardcoverError.NotConnected())
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an already-linked book shows its current match, and Remove match unlinks it") {
            runTest {
                val repo =
                    FakeHardcoverRepository().apply {
                        bookMatchResult =
                            AppResult.Success(HardcoverBookMatch.Linked(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir"), 2021))
                    }
                val vm = HardcoverMatchViewModel(BOOK, repo, library())
                vm.uiState.test {
                    advanceUntilIdle()
                    (expectMostRecentItem() as HardcoverMatchUiState.Ready).currentMatch shouldBe
                        HardcoverMatchedBook(427_578L, "Project Hail Mary", listOf("Andy Weir"), 2021, chosenByYou = false)
                    vm.events.test {
                        vm.removeMatch()
                        awaitItem() shouldBe HardcoverMatchEvent.MatchRemoved
                    }
                    cancelAndIgnoreRemainingEvents()
                }
                repo.unlinks shouldBe listOf(BookId(BOOK))
            }
        }
    })
