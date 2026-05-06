package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.usecase.collection.CreateCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.DeleteCollectionUseCase
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for AdminCollectionsViewModel.
 *
 * Tests cover:
 * - Initial `Loading` state before the observeAll flow emits
 * - `Ready` emission with collections after first emission
 * - `Error` state when the observe pipeline throws
 * - `createCollection` happy-path sets `createSuccess`
 * - `createCollection` failure surfaces as transient `error` on Ready
 * - `deleteCollection` happy-path clears the `deletingCollectionId` overlay
 * - `deleteCollection` failure surfaces as transient `error` on Ready
 * - `clearError` / `clearCreateSuccess` reset transient flags on Ready
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminCollectionsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val collectionRepository: CollectionRepository = mock()
        val createCollectionUseCase: CreateCollectionUseCase = mock()
        val deleteCollectionUseCase: DeleteCollectionUseCase = mock()
        val collectionsFlow = MutableStateFlow<List<Collection>>(emptyList())

        fun build(): AdminCollectionsViewModel =
            AdminCollectionsViewModel(
                collectionRepository = collectionRepository,
                createCollectionUseCase = createCollectionUseCase,
                deleteCollectionUseCase = deleteCollectionUseCase,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()
        every { fixture.collectionRepository.observeAll() } returns fixture.collectionsFlow
        everySuspend { fixture.collectionRepository.refreshFromServer() } returns Unit
        return fixture
    }

    // ========== Test Data Factories ==========

    private fun createCollection(
        id: String = "c1",
        name: String = "Collection $id",
        bookCount: Int = 0,
    ): Collection =
        Collection(
            id = id,
            name = name,
            bookCount = bookCount,
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )

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
            val repository: CollectionRepository = mock()
            every { repository.observeAll() } returns
                flow {
                    kotlinx.coroutines.awaitCancellation()
                }
            everySuspend { repository.refreshFromServer() } returns Unit

            // When
            val viewModel =
                AdminCollectionsViewModel(
                    collectionRepository = repository,
                    createCollectionUseCase = mock(),
                    deleteCollectionUseCase = mock(),
                )

            // Then
            assertIs<AdminCollectionsUiState.Loading>(viewModel.state.value)
        }

    // ========== Reactive Observation ==========

    @Test
    fun `Ready emitted with collections after first emission`() =
        runTest {
            // Given
            val fixture = createFixture()
            val first = createCollection(id = "a", name = "Alpha", bookCount = 2)
            val second = createCollection(id = "b", name = "Beta", bookCount = 0)
            fixture.collectionsFlow.value = listOf(first, second)

            // When
            val viewModel = fixture.build()
            advanceUntilIdle()

            // Then
            val ready = assertIs<AdminCollectionsUiState.Ready>(viewModel.state.value)
            assertEquals(listOf(first, second), ready.collections)
            assertFalse(ready.isCreating)
            assertFalse(ready.createSuccess)
            assertNull(ready.deletingCollectionId)
            assertNull(ready.error)
        }

    // ========== Error Handling ==========

    @Test
    fun `Error state emitted when observeAll flow throws`() =
        runTest {
            // Given
            val repository: CollectionRepository = mock()
            every { repository.observeAll() } returns
                flow {
                    throw RuntimeException("db broken")
                }
            everySuspend { repository.refreshFromServer() } returns Unit

            // When
            val viewModel =
                AdminCollectionsViewModel(
                    collectionRepository = repository,
                    createCollectionUseCase = mock(),
                    deleteCollectionUseCase = mock(),
                )
            advanceUntilIdle()

            // Then
            val err = assertIs<AdminCollectionsUiState.Error>(viewModel.state.value)
            assertEquals("db broken", err.message)
        }

    // ========== Create ==========

    @Test
    fun `createCollection happy-path sets createSuccess and clears isCreating`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.collectionsFlow.value = listOf(createCollection(id = "a"))
            everySuspend { fixture.createCollectionUseCase(any()) } returns
                Success(createCollection(id = "new", name = "New"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.createCollection("New")
            advanceUntilIdle()

            // Then
            val ready = assertIs<AdminCollectionsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.createSuccess)
            assertFalse(ready.isCreating)
            assertNull(ready.error)
        }

    @Test
    fun `createCollection failure surfaces as transient error on Ready`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.collectionsFlow.value = listOf(createCollection(id = "a"))
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend { fixture.createCollectionUseCase(any()) } returns
                Failure(com.calypsan.listenup.api.error.ValidationError(message = "duplicate name"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.createCollection("Dup")
            advanceUntilIdle()

            // Then
            val ready = assertIs<AdminCollectionsUiState.Ready>(viewModel.state.value)
            assertEquals("duplicate name", ready.error)
            assertFalse(ready.isCreating)
            assertFalse(ready.createSuccess)
        }

    // ========== Delete ==========

    @Test
    fun `deleteCollection happy-path clears deletingCollectionId`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.collectionsFlow.value = listOf(createCollection(id = "a"))
            everySuspend { fixture.deleteCollectionUseCase("a") } returns Success(Unit)
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.deleteCollection("a")
            advanceUntilIdle()

            // Then
            val ready = assertIs<AdminCollectionsUiState.Ready>(viewModel.state.value)
            assertNull(ready.deletingCollectionId)
            assertNull(ready.error)
        }

    @Test
    fun `deleteCollection failure surfaces as transient error on Ready`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.collectionsFlow.value = listOf(createCollection(id = "a"))
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend { fixture.deleteCollectionUseCase("a") } returns
                Failure(com.calypsan.listenup.api.error.ValidationError(message = "not permitted"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.deleteCollection("a")
            advanceUntilIdle()

            // Then
            val ready = assertIs<AdminCollectionsUiState.Ready>(viewModel.state.value)
            assertNull(ready.deletingCollectionId)
            assertEquals("not permitted", ready.error)
        }

    // ========== Transient State Clearing ==========

    @Test
    fun `clearError resets Ready error to null`() =
        runTest {
            // Given — a Ready state with an error
            val fixture = createFixture()
            fixture.collectionsFlow.value = listOf(createCollection(id = "a"))
            // Body-level message convention: pass a typed AppError so the
            // user-facing message survives delegation to the ViewModel.
            everySuspend { fixture.deleteCollectionUseCase("a") } returns
                Failure(com.calypsan.listenup.api.error.ValidationError(message = "boom"))
            val viewModel = fixture.build()
            advanceUntilIdle()
            viewModel.deleteCollection("a")
            advanceUntilIdle()
            assertEquals(
                "boom",
                assertIs<AdminCollectionsUiState.Ready>(viewModel.state.value).error,
            )

            // When
            viewModel.clearError()

            // Then
            val ready = assertIs<AdminCollectionsUiState.Ready>(viewModel.state.value)
            assertNull(ready.error)
        }

    @Test
    fun `clearCreateSuccess resets createSuccess flag`() =
        runTest {
            // Given — a Ready state with createSuccess set
            val fixture = createFixture()
            fixture.collectionsFlow.value = listOf(createCollection(id = "a"))
            everySuspend { fixture.createCollectionUseCase(any()) } returns
                Success(createCollection(id = "new"))
            val viewModel = fixture.build()
            advanceUntilIdle()
            viewModel.createCollection("New")
            advanceUntilIdle()
            assertTrue(
                assertIs<AdminCollectionsUiState.Ready>(viewModel.state.value).createSuccess,
            )

            // When
            viewModel.clearCreateSuccess()

            // Then
            val ready = assertIs<AdminCollectionsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.createSuccess)
        }
}
