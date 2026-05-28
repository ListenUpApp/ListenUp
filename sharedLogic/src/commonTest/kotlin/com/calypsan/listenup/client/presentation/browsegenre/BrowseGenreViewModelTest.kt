package com.calypsan.listenup.client.presentation.browsegenre

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.presentation.error.userMessageFor
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
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
 * Tests for BrowseGenreViewModel.
 *
 * Backfill characterization tests for the screen shipped in PR #329. Covers:
 * - Initial Loading before observeAll emits
 * - Ready carries the observed genre tree
 * - selectGenre loads books via browseBooks(includeDescendants = current flag)
 * - toggleIncludeDescendants re-fetches the selected genre's books with the flipped flag
 * - browseBooks failure surfaces a transient error
 * - clearError resets the transient error
 *
 * Uses Mokkery for GenreRepository, MutableStateFlow upstream (not flowOf) per
 * test_stateflow_use_mutablestateflow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseGenreViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeEach { Dispatchers.setMain(testDispatcher) }
        afterEach { Dispatchers.resetMain() }

        fun genre(
            id: String = "g1",
            name: String = "Fiction",
            slug: String = "fiction",
            path: String = "/fiction",
        ): Genre = Genre(id = id, name = name, slug = slug, path = path)

        fun createFixture(
            genres: List<Genre> = emptyList(),
        ): Pair<GenreRepository, BrowseGenreViewModel> {
            val repo: GenreRepository = mock()
            every { repo.observeAll() } returns MutableStateFlow(genres)
            val vm = BrowseGenreViewModel(genreRepository = repo, errorBus = ErrorBus())
            return repo to vm
        }

        test("initial state is Loading before observeAll emits") {
            runTest {
                val (_, vm) = createFixture()
                vm.state.value.shouldBeInstanceOf<BrowseGenreUiState.Loading>()
            }
        }

        test("Ready carries the observed genres") {
            runTest {
                val fiction = genre(id = "fiction", name = "Fiction", path = "/fiction")
                val (_, vm) = createFixture(genres = listOf(fiction))
                backgroundScope.launch { vm.state.collect { } }
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BrowseGenreUiState.Ready>()
                ready.genres shouldBe listOf(fiction)
            }
        }

        test("selectGenre loads books for that genre") {
            runTest {
                val fiction = genre(id = "fiction")
                val (repo, vm) = createFixture(genres = listOf(fiction))
                everySuspend { repo.browseBooks(any(), any(), any()) } returns
                    AppResult.Success(listOf(BookId("b1"), BookId("b2")))
                backgroundScope.launch { vm.state.collect { } }
                advanceUntilIdle()

                vm.selectGenre(GenreId("fiction"))
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BrowseGenreUiState.Ready>()
                ready.selectedGenreId shouldBe GenreId("fiction")
                ready.books shouldBe listOf(BookId("b1"), BookId("b2"))
                ready.isFetchingBooks shouldBe false
                verifySuspend { repo.browseBooks(GenreId("fiction"), false, any()) }
            }
        }

        test("toggleIncludeDescendants re-fetches selected genre with flipped flag") {
            runTest {
                val fiction = genre(id = "fiction")
                val (repo, vm) = createFixture(genres = listOf(fiction))
                everySuspend { repo.browseBooks(any(), any(), any()) } returns
                    AppResult.Success(listOf(BookId("b1")))
                backgroundScope.launch { vm.state.collect { } }
                advanceUntilIdle()

                vm.selectGenre(GenreId("fiction"))
                advanceUntilIdle()
                vm.toggleIncludeDescendants()
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BrowseGenreUiState.Ready>()
                ready.includeDescendants shouldBe true
                verifySuspend { repo.browseBooks(GenreId("fiction"), true, any()) }
            }
        }

        test("browseBooks failure surfaces transient error") {
            runTest {
                val fiction = genre(id = "fiction")
                val (repo, vm) = createFixture(genres = listOf(fiction))
                val error = TransportError.Server5xx(statusCode = 500, debugInfo = "boom")
                everySuspend { repo.browseBooks(any(), any(), any()) } returns AppResult.Failure(error)
                backgroundScope.launch { vm.state.collect { } }
                advanceUntilIdle()

                vm.selectGenre(GenreId("fiction"))
                advanceUntilIdle()

                val ready = vm.state.value.shouldBeInstanceOf<BrowseGenreUiState.Ready>()
                ready.error shouldBe userMessageFor(error)
                ready.isFetchingBooks shouldBe false
            }
        }

        test("clearError resets the transient error to null") {
            runTest {
                val fiction = genre(id = "fiction")
                val (repo, vm) = createFixture(genres = listOf(fiction))
                everySuspend { repo.browseBooks(any(), any(), any()) } returns
                    AppResult.Failure(TransportError.Server5xx(statusCode = 500, debugInfo = "x"))
                backgroundScope.launch { vm.state.collect { } }
                advanceUntilIdle()
                vm.selectGenre(GenreId("fiction"))
                advanceUntilIdle()

                vm.clearError()
                advanceUntilIdle()

                vm.state.value
                    .shouldBeInstanceOf<BrowseGenreUiState.Ready>()
                    .error shouldBe null
            }
        }
    })
