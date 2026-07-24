package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.GenreRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for AdminCategoriesViewModel.
 *
 * Tests cover:
 * - Initial `Loading` state before the observeAll flow has emitted
 * - `Ready` emission with genres + computed tree + totalBookCount
 * - `Error` state when the observe pipeline throws
 * - `toggleExpanded` / `collapseAll` mutate Ready.expandedIds
 * - `createGenre` happy-path delegates to the repository
 * - `createGenre` failure surfaces as a transient `error` on Ready
 * - `clearError` clears the transient error on Ready
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminCategoriesViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        class TestFixture {
            val genreRepository: GenreRepository = mock()
            val genresFlow = MutableStateFlow<List<Genre>>(emptyList())

            fun build(): AdminCategoriesViewModel = AdminCategoriesViewModel(genreRepository, errorBus = ErrorBus())
        }

        fun createFixture(): TestFixture {
            val fixture = TestFixture()
            every { fixture.genreRepository.observeAll() } returns fixture.genresFlow
            everySuspend { fixture.genreRepository.createGenre(any(), any(), any()) } returns
                AppResult.Success(
                    com.calypsan.listenup.core
                        .GenreId("created"),
                )
            everySuspend { fixture.genreRepository.updateGenre(any(), any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.genreRepository.deleteGenre(any()) } returns AppResult.Success(Unit)
            everySuspend { fixture.genreRepository.moveGenre(any(), any()) } returns AppResult.Success(Unit)
            return fixture
        }

        // ========== Test Data Factories ==========

        fun createGenre(
            id: String = "g1",
            name: String = "Fiction",
            slug: String = "fiction",
            path: String = "/fiction",
            bookCount: Int = 0,
        ): Genre =
            Genre(
                id = id,
                name = name,
                slug = slug,
                path = path,
                bookCount = bookCount,
            )

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        // ========== Initial State ==========

        test("initial state is Loading before observeAll emits") {
            runTest {
                // Given a repository whose observeAll never emits
                val genreRepository: GenreRepository = mock()
                every { genreRepository.observeAll() } returns
                    flow {
                        // suspend forever — no emission
                        kotlinx.coroutines.awaitCancellation()
                    }

                // When
                val viewModel = AdminCategoriesViewModel(genreRepository, errorBus = ErrorBus())

                // Then
                viewModel.state.value.shouldBeInstanceOf<AdminCategoriesUiState.Loading>()
            }
        }

        // ========== Reactive Observation ==========

        test("Ready emitted with genres tree and totalBookCount after first emission") {
            runTest {
                // Given
                val fixture = createFixture()
                val fiction = createGenre(id = "fiction", name = "Fiction", path = "/fiction", bookCount = 3)
                val fantasy =
                    createGenre(id = "fantasy", name = "Fantasy", path = "/fiction/fantasy", bookCount = 5)
                fixture.genresFlow.value = listOf(fiction, fantasy)

                // When
                val viewModel = fixture.build()
                advanceUntilIdle()

                // Then
                val ready = viewModel.state.value.shouldBeInstanceOf<AdminCategoriesUiState.Ready>()
                ready.genres shouldBe listOf(fiction, fantasy)
                ready.totalBookCount shouldBe 8
                // Tree has one root ("Fiction") with one child ("Fantasy")
                ready.tree.size shouldBe 1
                ready.tree[0].genre.id shouldBe "fiction"
                ready.tree[0].children.size shouldBe 1
                ready.tree[0]
                    .children[0]
                    .genre.id shouldBe "fantasy"
                ready.expandedIds.isEmpty() shouldBe true
                ready.error shouldBe null
            }
        }

        // ========== Error Handling ==========

        test("Error state emitted when observeAll flow throws") {
            runTest {
                // Given
                val genreRepository: GenreRepository = mock()
                every { genreRepository.observeAll() } returns
                    flow {
                        throw RuntimeException("db broken")
                    }

                // When
                val viewModel = AdminCategoriesViewModel(genreRepository, errorBus = ErrorBus())
                advanceUntilIdle()

                // Then — the thrown Throwable is mapped to a typed AppError (InternalError),
                // with the original message preserved in debugInfo for diagnostics.
                val err = viewModel.state.value.shouldBeInstanceOf<AdminCategoriesUiState.Error>()
                val internal = err.error.shouldBeInstanceOf<InternalError>()
                (internal.debugInfo?.contains("db broken") == true) shouldBe true
            }
        }

        // ========== Expand / Collapse ==========

        test("toggleExpanded adds then removes id from Ready expandedIds") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.genresFlow.value = listOf(createGenre(id = "fiction"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When — first toggle adds
                viewModel.toggleExpanded("fiction")
                val afterAdd = viewModel.state.value.shouldBeInstanceOf<AdminCategoriesUiState.Ready>()
                afterAdd.expandedIds shouldBe setOf("fiction")

                // When — second toggle removes
                viewModel.toggleExpanded("fiction")
                val afterRemove = viewModel.state.value.shouldBeInstanceOf<AdminCategoriesUiState.Ready>()
                afterRemove.expandedIds.isEmpty() shouldBe true
            }
        }

        // ========== Mutations ==========

        test("createGenre delegates to repository and auto-expands parent") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.genresFlow.value = listOf(createGenre(id = "fiction"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.createGenre(name = "Fantasy", parentId = "fiction")
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.genreRepository.createGenre(any(), any(), any()) }
                val ready = viewModel.state.value.shouldBeInstanceOf<AdminCategoriesUiState.Ready>()
                ready.expandedIds.contains("fiction") shouldBe true
                ready.isSaving shouldBe false
                ready.error shouldBe null
            }
        }

        test("createGenre failure surfaces as transient error on Ready") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.genresFlow.value = listOf(createGenre(id = "fiction"))
                val failureError = TransportError.Server4xx(statusCode = 409, debugInfo = "duplicate name")
                everySuspend { fixture.genreRepository.createGenre(any(), any()) } returns
                    AppResult.Failure(failureError)
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.createGenre(name = "Fiction", parentId = null)
                advanceUntilIdle()

                // Then — the typed AppError itself is carried in state (no longer flattened to a string).
                val ready = viewModel.state.value.shouldBeInstanceOf<AdminCategoriesUiState.Ready>()
                ready.error shouldBe failureError
                ready.isSaving shouldBe false
            }
        }

        test("clearError resets Ready error to null") {
            runTest {
                // Given — a Ready state with an error
                val fixture = createFixture()
                fixture.genresFlow.value = listOf(createGenre(id = "fiction"))
                val failureError = TransportError.Server4xx(statusCode = 500, debugInfo = "boom")
                everySuspend { fixture.genreRepository.createGenre(any(), any()) } returns
                    AppResult.Failure(failureError)
                val viewModel = fixture.build()
                advanceUntilIdle()
                viewModel.createGenre(name = "X", parentId = null)
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<AdminCategoriesUiState.Ready>()
                    .error shouldBe failureError

                // When
                viewModel.clearError()

                // Then
                val ready = viewModel.state.value.shouldBeInstanceOf<AdminCategoriesUiState.Ready>()
                ready.error shouldBe null
            }
        }
    })
