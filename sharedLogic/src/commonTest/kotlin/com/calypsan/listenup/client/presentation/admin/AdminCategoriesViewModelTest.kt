package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.presentation.error.userMessageFor
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.calypsan.listenup.core.error.ErrorBus

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
class AdminCategoriesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val genreRepository: GenreRepository = mock()
        val genresFlow = MutableStateFlow<List<Genre>>(emptyList())

        fun build(): AdminCategoriesViewModel = AdminCategoriesViewModel(genreRepository, errorBus = ErrorBus())
    }

    private fun createFixture(): TestFixture {
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

    companion object {
        private fun createGenre(
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
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Initial State ==========

    @Test
    fun `initial state is Loading before observeAll emits`() =
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
            assertIs<AdminCategoriesUiState.Loading>(viewModel.state.value)
        }

    // ========== Reactive Observation ==========

    @Test
    fun `Ready emitted with genres tree and totalBookCount after first emission`() =
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
            val ready = assertIs<AdminCategoriesUiState.Ready>(viewModel.state.value)
            assertEquals(listOf(fiction, fantasy), ready.genres)
            assertEquals(8, ready.totalBookCount)
            // Tree has one root ("Fiction") with one child ("Fantasy")
            assertEquals(1, ready.tree.size)
            assertEquals("fiction", ready.tree[0].genre.id)
            assertEquals(1, ready.tree[0].children.size)
            assertEquals(
                "fantasy",
                ready.tree[0]
                    .children[0]
                    .genre.id,
            )
            assertTrue(ready.expandedIds.isEmpty())
            assertNull(ready.error)
        }

    // ========== Error Handling ==========

    @Test
    fun `Error state emitted when observeAll flow throws`() =
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

            // Then
            val err = assertIs<AdminCategoriesUiState.Error>(viewModel.state.value)
            assertEquals("db broken", err.message)
        }

    // ========== Expand / Collapse ==========

    @Test
    fun `toggleExpanded adds then removes id from Ready expandedIds`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.genresFlow.value = listOf(createGenre(id = "fiction"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When — first toggle adds
            viewModel.toggleExpanded("fiction")
            val afterAdd = assertIs<AdminCategoriesUiState.Ready>(viewModel.state.value)
            assertEquals(setOf("fiction"), afterAdd.expandedIds)

            // When — second toggle removes
            viewModel.toggleExpanded("fiction")
            val afterRemove = assertIs<AdminCategoriesUiState.Ready>(viewModel.state.value)
            assertTrue(afterRemove.expandedIds.isEmpty())
        }

    // ========== Mutations ==========

    @Test
    fun `createGenre delegates to repository and auto-expands parent`() =
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
            val ready = assertIs<AdminCategoriesUiState.Ready>(viewModel.state.value)
            assertTrue(ready.expandedIds.contains("fiction"))
            assertEquals(false, ready.isSaving)
            assertNull(ready.error)
        }

    @Test
    fun `createGenre failure surfaces as transient error on Ready`() =
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

            // Then
            val ready = assertIs<AdminCategoriesUiState.Ready>(viewModel.state.value)
            assertEquals(userMessageFor(failureError), ready.error)
            assertEquals(false, ready.isSaving)
        }

    @Test
    fun `clearError resets Ready error to null`() =
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
            assertEquals(
                userMessageFor(failureError),
                assertIs<AdminCategoriesUiState.Ready>(viewModel.state.value).error,
            )

            // When
            viewModel.clearError()

            // Then
            val ready = assertIs<AdminCategoriesUiState.Ready>(viewModel.state.value)
            assertNull(ready.error)
        }
}
