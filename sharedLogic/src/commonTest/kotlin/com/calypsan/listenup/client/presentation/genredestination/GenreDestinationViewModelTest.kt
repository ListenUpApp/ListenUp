package com.calypsan.listenup.client.presentation.genredestination

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.core.GenreId
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for GenreDestinationViewModel.
 *
 * Covers:
 * - A genre with sub-genres defaults to subtree scope (includeSubGenres = true), carrying its
 *   parent breadcrumb, its direct children, and the subtree stats/books.
 * - [GenreDestinationViewModel.toggleIncludeSubGenres] narrows to the direct-books scope.
 * - A leaf genre has no sub-genres, forces includeSubGenres = false, and still resolves its full
 *   ancestor chain.
 * - An unknown genre id surfaces [GenreDestinationUiState.NotFound].
 *
 * Uses Mokkery for the repositories and MutableStateFlow upstreams (not flowOf) per
 * test_stateflow_use_mutablestateflow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenreDestinationViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeEach { Dispatchers.setMain(testDispatcher) }
        afterEach { Dispatchers.resetMain() }

        val fiction = Genre(id = "fiction", name = "Fiction", slug = "fiction", path = "/fiction", bookCount = 10)
        val fantasy =
            Genre(
                id = "fantasy",
                name = "Fantasy",
                slug = "fantasy",
                path = "/fiction/fantasy",
                bookCount = 5,
            )
        val epicFantasy =
            Genre(
                id = "epic-fantasy",
                name = "Epic Fantasy",
                slug = "epic-fantasy",
                path = "/fiction/fantasy/epic-fantasy",
                bookCount = 2,
            )
        val urbanFantasy =
            Genre(
                id = "urban-fantasy",
                name = "Urban Fantasy",
                slug = "urban-fantasy",
                path = "/fiction/fantasy/urban-fantasy",
                bookCount = 3,
            )
        val tree = listOf(fiction, fantasy, epicFantasy, urbanFantasy)

        val subtreeStats = FacetStats(bookCount = 5, totalDurationMs = 500_000L)
        val directStats = FacetStats(bookCount = 1, totalDurationMs = 100_000L)
        val subtreeBooks = listOf(TestData.bookListItem(id = "book-subtree-1", title = "Subtree Book"))
        val directBooks = listOf(TestData.bookListItem(id = "book-direct-1", title = "Direct Book"))

        data class Fixture(
            val genreRepository: GenreRepository,
            val bookRepository: BookRepository,
            val viewModel: GenreDestinationViewModel,
        )

        fun createFixture(genres: List<Genre> = tree): Fixture {
            val genreRepository: GenreRepository = mock()
            val bookRepository: BookRepository = mock()
            every { genreRepository.observeAll() } returns MutableStateFlow(genres)
            every { bookRepository.observeBookListItems(any()) } returns MutableStateFlow(emptyList())

            everySuspend { genreRepository.getGenreStats(GenreId("fantasy"), true) } returns
                AppResult.Success(subtreeStats)
            everySuspend { genreRepository.getGenreStats(GenreId("fantasy"), false) } returns
                AppResult.Success(directStats)
            everySuspend { genreRepository.browseBooks(GenreId("fantasy"), true, any()) } returns
                AppResult.Success(subtreeBooks.map { it.id })
            everySuspend { genreRepository.browseBooks(GenreId("fantasy"), false, any()) } returns
                AppResult.Success(directBooks.map { it.id })
            every { bookRepository.observeBookListItems(subtreeBooks.map { it.id.value }) } returns
                MutableStateFlow(subtreeBooks)
            every { bookRepository.observeBookListItems(directBooks.map { it.id.value }) } returns
                MutableStateFlow(directBooks)

            everySuspend { genreRepository.getGenreStats(GenreId("epic-fantasy"), false) } returns
                AppResult.Success(FacetStats.EMPTY)
            everySuspend { genreRepository.browseBooks(GenreId("epic-fantasy"), false, any()) } returns
                AppResult.Success(emptyList())

            val vm = GenreDestinationViewModel(genreRepository = genreRepository, bookRepository = bookRepository)
            return Fixture(genreRepository, bookRepository, vm)
        }

        test("initial state is Loading before load() is called") {
            runTest {
                val fixture = createFixture()
                fixture.viewModel.state.value.shouldBeInstanceOf<GenreDestinationUiState.Loading>()
            }
        }

        test("genre with sub-genres defaults to subtree scope") {
            runTest {
                val fixture = createFixture()
                backgroundScope.launch { fixture.viewModel.state.collect { } }

                fixture.viewModel.load(GenreId("fantasy"))
                advanceUntilIdle()

                val ready = fixture.viewModel.state.value.shouldBeInstanceOf<GenreDestinationUiState.Ready>()
                ready.identity.name shouldBe "Fantasy"
                ready.identity.slug shouldBe "fantasy"
                ready.breadcrumb shouldBe listOf(GenreCrumb(genreId = GenreId("fiction"), name = "Fiction"))
                ready.subGenres shouldBe
                    listOf(
                        SubGenre(genreId = GenreId("epic-fantasy"), name = "Epic Fantasy", bookCount = 2),
                        SubGenre(genreId = GenreId("urban-fantasy"), name = "Urban Fantasy", bookCount = 3),
                    )
                ready.hasSubs shouldBe true
                ready.includeSubGenres shouldBe true
                ready.stats shouldBe subtreeStats
                ready.books shouldBe subtreeBooks
            }
        }

        test("toggleIncludeSubGenres narrows to direct-books scope") {
            runTest {
                val fixture = createFixture()
                backgroundScope.launch { fixture.viewModel.state.collect { } }
                fixture.viewModel.load(GenreId("fantasy"))
                advanceUntilIdle()

                fixture.viewModel.toggleIncludeSubGenres()
                advanceUntilIdle()

                val ready = fixture.viewModel.state.value.shouldBeInstanceOf<GenreDestinationUiState.Ready>()
                ready.includeSubGenres shouldBe false
                ready.stats shouldBe directStats
                ready.books shouldBe directBooks
            }
        }

        test("leaf genre has no sub-genres and resolves its full ancestor chain") {
            runTest {
                val fixture = createFixture()
                backgroundScope.launch { fixture.viewModel.state.collect { } }

                fixture.viewModel.load(GenreId("epic-fantasy"))
                advanceUntilIdle()

                val ready = fixture.viewModel.state.value.shouldBeInstanceOf<GenreDestinationUiState.Ready>()
                ready.hasSubs shouldBe false
                ready.subGenres shouldBe emptyList()
                ready.includeSubGenres shouldBe false
                ready.breadcrumb shouldBe
                    listOf(
                        GenreCrumb(genreId = GenreId("fiction"), name = "Fiction"),
                        GenreCrumb(genreId = GenreId("fantasy"), name = "Fantasy"),
                    )
            }
        }

        test("unknown genre id surfaces NotFound") {
            runTest {
                val fixture = createFixture()
                backgroundScope.launch { fixture.viewModel.state.collect { } }

                fixture.viewModel.load(GenreId("does-not-exist"))
                advanceUntilIdle()

                fixture.viewModel.state.value.shouldBeInstanceOf<GenreDestinationUiState.NotFound>()
            }
        }
    })
