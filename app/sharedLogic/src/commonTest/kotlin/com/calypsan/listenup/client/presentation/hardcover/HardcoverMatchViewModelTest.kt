package com.calypsan.listenup.client.presentation.hardcover

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookMatch
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.presentation.settings.FakeHardcoverRepository
import com.calypsan.listenup.client.presentation.settings.RestoredMatch
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val BOOK = "b1"
private val REAL = HardcoverBookCandidate(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir"), 2021, 8_107)
private val SUMMARY = HardcoverBookCandidate(1L, null, "Project Hail Mary (Summary)", listOf("Quick Reads"), 2022, 0)

private fun library(present: Boolean = true): BookRepository =
    mock<BookRepository>().also { repo ->
        everySuspend { repo.getBookListItem(any()) } returns
            if (present) TestData.bookListItem(id = BOOK, title = "Project Hail Mary", authorName = "Andy Weir") else null
    }

private val REAL_ROW = HardcoverCandidateRow(427_578L, 9_001L, "Project Hail Mary", listOf("Andy Weir"), 2021, 8_107, true, true)
private val PREVIOUS = HardcoverBookMatch.Linked(7L, 70L, "Project Hail Mary (Summary)", listOf("Quick Reads"), 2022)

/** A [Clock] the test moves by hand. */
private class MutableClock(
    var now: Instant = Instant.fromEpochMilliseconds(1_000_000L),
) : Clock {
    override fun now(): Instant = now
}

private fun TestScope.matchViewModel(
    repo: FakeHardcoverRepository,
    books: BookRepository = library(),
    errorBus: ErrorBus = ErrorBus(),
    clock: Clock = MutableClock(),
) = HardcoverMatchViewModel(BOOK, repo, books, appScope = this, errorBus = errorBus, clock = clock)

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
                val vm = matchViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    val ready = expectMostRecentItem().shouldBeInstanceOf<HardcoverMatchUiState.Ready>()
                    ready.query shouldBe "Project Hail Mary"
                    ready.bookAuthors shouldBe "Andy Weir"
                    ready.search
                        .shouldBeInstanceOf<HardcoverSearchState.Results>()
                        .rows
                        .map { it.hcBookId } shouldBe
                        listOf(427_578L, 1L)
                }
                repo.searches shouldBe listOf("Project Hail Mary")
            }
        }

        test("a book no longer in the library says so and searches nothing") {
            runTest {
                val repo = FakeHardcoverRepository()
                val vm = matchViewModel(repo, library(present = false))
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
                val vm = matchViewModel(repo)
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
                val vm = matchViewModel(repo)
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
                val vm = matchViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.link(427_578L)
                    advanceUntilIdle()
                    (expectMostRecentItem() as HardcoverMatchUiState.Ready).linkingId shouldBe 427_578L
                    vm.events.test {
                        repo.linkGate!!.complete(Unit)
                        awaitItem() shouldBe HardcoverMatchEvent.Linked(REAL_ROW, replaced = null)
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
                val vm = matchViewModel(repo)
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
                val vm = matchViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    (expectMostRecentItem() as HardcoverMatchUiState.Ready).currentMatch shouldBe
                        HardcoverMatchedBook(
                            427_578L,
                            "Project Hail Mary",
                            listOf("Andy Weir"),
                            2021,
                            chosenByYou = false,
                            hcEditionId = 9_001L,
                        )
                    vm.events.test {
                        vm.removeMatch()
                        awaitItem() shouldBe HardcoverMatchEvent.MatchRemoved
                    }
                    cancelAndIgnoreRemainingEvents()
                }
                repo.unlinks shouldBe listOf(BookId(BOOK))
            }
        }

        test("undoing a first link removes the match again") {
            runTest {
                val repo = FakeHardcoverRepository().apply { searchResult = AppResult.Success(listOf(REAL)) }
                val vm = matchViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.events.test {
                        vm.link(427_578L)
                        awaitItem() shouldBe HardcoverMatchEvent.Linked(REAL_ROW, replaced = null)
                    }
                    vm.undoLink()
                    advanceUntilIdle()
                    cancelAndIgnoreRemainingEvents()
                }
                repo.unlinks shouldBe listOf(BookId(BOOK))
                repo.links.size shouldBe 1
            }
        }

        test("undoing a changed match links the previous book and edition again") {
            runTest {
                val repo =
                    FakeHardcoverRepository().apply {
                        searchResult = AppResult.Success(listOf(REAL))
                        bookMatchResult = AppResult.Success(PREVIOUS)
                    }
                val vm = matchViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.events.test {
                        vm.link(427_578L)
                        awaitItem() shouldBe
                            HardcoverMatchEvent.Linked(
                                REAL_ROW,
                                replaced = HardcoverMatchedBook(7L, "Project Hail Mary (Summary)", listOf("Quick Reads"), 2022, false, 70L),
                            )
                    }
                    vm.undoLink()
                    advanceUntilIdle()
                    cancelAndIgnoreRemainingEvents()
                }
                repo.links shouldBe listOf(Triple(BookId(BOOK), 427_578L, 9_001L), Triple(BookId(BOOK), 7L, 70L))
                repo.unlinks shouldBe emptyList()
            }
        }

        test("undoing a change from an ASIN match puts it back as ASIN, at its edition, not as the user's pick") {
            runTest {
                val asinMatch = PREVIOUS.copy(method = HardcoverMatchMethod.ASIN)
                val repo =
                    FakeHardcoverRepository().apply {
                        searchResult = AppResult.Success(listOf(REAL))
                        bookMatchResult = AppResult.Success(asinMatch)
                    }
                val vm = matchViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.link(427_578L)
                    advanceUntilIdle()
                    vm.undoLink()
                    advanceUntilIdle()
                    val current = expectMostRecentItem().shouldBeInstanceOf<HardcoverMatchUiState.Ready>().currentMatch!!
                    current.method shouldBe HardcoverMatchMethod.ASIN
                    current.chosenByYou shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
                repo.restores shouldBe listOf(RestoredMatch(BookId(BOOK), 7L, 70L, HardcoverMatchMethod.ASIN))
                repo.links shouldBe listOf(Triple(BookId(BOOK), 427_578L, 9_001L))
            }
        }

        test("undo works once, never after its window, and never after another action") {
            runTest {
                val repo = FakeHardcoverRepository().apply { searchResult = AppResult.Success(listOf(REAL)) }
                val clock = MutableClock()
                val vm = matchViewModel(repo, clock = clock)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.link(427_578L)
                    advanceUntilIdle()
                    vm.undoLink()
                    vm.undoLink()
                    advanceUntilIdle()
                    repo.unlinks.size shouldBe 1

                    vm.link(427_578L)
                    advanceUntilIdle()
                    clock.now += 31.seconds
                    vm.undoLink()
                    advanceUntilIdle()
                    repo.unlinks.size shouldBe 1

                    vm.link(427_578L)
                    advanceUntilIdle()
                    vm.removeMatch()
                    advanceUntilIdle()
                    repo.unlinks.size shouldBe 2
                    vm.undoLink()
                    advanceUntilIdle()
                    repo.unlinks.size shouldBe 2
                    repo.links.size shouldBe 3
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("an undo the server refuses goes to the error bus, since the screen may be gone") {
            runTest {
                val repo = FakeHardcoverRepository().apply { searchResult = AppResult.Success(listOf(REAL)) }
                val errorBus = ErrorBus()
                val vm = matchViewModel(repo, errorBus = errorBus)
                errorBus.errors.test {
                    vm.uiState.test {
                        advanceUntilIdle()
                        vm.link(427_578L)
                        advanceUntilIdle()
                        repo.unlinkResult = AppResult.Failure(HardcoverError.Unavailable())
                        vm.undoLink()
                        advanceUntilIdle()
                        cancelAndIgnoreRemainingEvents()
                    }
                    awaitItem() shouldBe HardcoverError.Unavailable()
                }
            }
        }

        test("results split into the book's author first and the rest") {
            val results = HardcoverSearchState.Results(listOf(REAL_ROW, REAL_ROW.copy(hcBookId = 1L, sharesAuthor = false)))
            results.byAuthor.map { it.hcBookId } shouldBe listOf(427_578L)
            results.others.map { it.hcBookId } shouldBe listOf(1L)
        }

        test("nothing found suggests the title and the author; weak results suggest both together") {
            runTest {
                val repo = FakeHardcoverRepository().apply { searchResult = AppResult.Success(emptyList()) }
                val vm = matchViewModel(repo)
                vm.uiState.test {
                    advanceUntilIdle()
                    vm.onQueryChange("Project Hail Mary (Unabridged)")
                    vm.search()
                    advanceUntilIdle()
                    (expectMostRecentItem() as HardcoverMatchUiState.Ready).suggestions shouldBe
                        listOf("Project Hail Mary", "Andy Weir")
                    repo.searchResult = AppResult.Success(listOf(SUMMARY))
                    vm.searchFor("Hail Mary")
                    advanceUntilIdle()
                    val weak = expectMostRecentItem() as HardcoverMatchUiState.Ready
                    weak.query shouldBe "Hail Mary"
                    weak.suggestions shouldBe listOf("Project Hail Mary Andy Weir")
                    repo.searchResult = AppResult.Success(listOf(REAL))
                    vm.searchFor("Project Hail Mary Andy Weir")
                    advanceUntilIdle()
                    (expectMostRecentItem() as HardcoverMatchUiState.Ready).suggestions shouldBe emptyList()
                    cancelAndIgnoreRemainingEvents()
                }
                repo.searches shouldBe
                    listOf("Project Hail Mary", "Project Hail Mary (Unabridged)", "Hail Mary", "Project Hail Mary Andy Weir")
            }
        }
    })
