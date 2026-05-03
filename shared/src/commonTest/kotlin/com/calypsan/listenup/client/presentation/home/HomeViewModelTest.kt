package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.assertTrue

/**
 * Tests for HomeViewModel.
 *
 * Tests cover:
 * - Initial state and reactive observation
 * - Continue listening observation (reactive updates)
 * - User observation and name extraction
 * - Greeting generation (userName updates)
 * - State derived properties
 * - Snackbar emission on continue-listening failure
 *
 * Uses Mokkery for mocking repositories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val homeRepository: HomeRepository = mock()
        val userRepository: UserRepository = mock()
        val shelfRepository: ShelfRepository = mock()
        val syncRepository: SyncRepository = mock()
        val userFlow = MutableStateFlow<User?>(null)
        val continueListeningFlow = MutableStateFlow<List<ContinueListeningBook>>(emptyList())
        val scanProgressFlow =
            MutableStateFlow<com.calypsan.listenup.client.data.sync.sse.ScanProgressState?>(null)
        val syncStateFlow = MutableStateFlow<SyncState>(SyncState.Idle)
        var currentHour: Int = 10 // Default to morning

        fun build(): HomeViewModel {
            every { syncRepository.scanProgress } returns scanProgressFlow
            every { syncRepository.syncState } returns syncStateFlow
            return HomeViewModel(
                homeRepository = homeRepository,
                userRepository = userRepository,
                shelfRepository = shelfRepository,
                syncRepository = syncRepository,
                currentHour = { currentHour },
            )
        }
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stubs for reactive observation
        every { fixture.userRepository.observeCurrentUser() } returns fixture.userFlow
        every { fixture.homeRepository.observeContinueListening(any()) } returns
            fixture.continueListeningFlow
        every { fixture.shelfRepository.observeMyShelves(any()) } returns flowOf(emptyList())

        return fixture
    }

    private fun TestScope.keepStateHot(viewModel: HomeViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    // ========== Test Data Factories ==========

    private fun createUser(
        id: String = "user-1",
        email: String = "john@example.com",
        displayName: String = "John Smith",
        isAdmin: Boolean = false,
    ): User =
        User(
            id =
                UserId(id),
            email = email,
            displayName = displayName,
            isAdmin = isAdmin,
            createdAtMs = 1704067200000L,
            updatedAtMs = 1704067200000L,
        )

    private fun createContinueListeningBook(
        bookId: String = "book-1",
        title: String = "Test Book",
        authorNames: String = "Test Author",
        progress: Float = 0.5f,
        currentPositionMs: Long = 1_800_000L,
        totalDurationMs: Long = 3_600_000L,
    ): ContinueListeningBook =
        ContinueListeningBook(
            bookId = bookId,
            title = title,
            authorNames = authorNames,
            coverPath = null,
            progress = progress,
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            lastPlayedAt = "2024-01-01T00:00:00Z",
        )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Initial State Tests ==========

    @Test
    fun `init starts observation and transitions to Ready after first emission`() =
        runTest {
            // Given
            val fixture = createFixture()

            // When - viewModel created, stateIn pipeline subscribes on first collect
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then - state should be Ready after combine emits
            assertIs<HomeUiState.Ready>(viewModel.state.value)
        }

    // ========== Reactive Observation Tests ==========

    @Test
    fun `observeContinueListening updates state when flow emits`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()
            val initial = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertTrue(initial.continueListening.isEmpty())

            // When - flow emits new data
            val books =
                listOf(
                    createContinueListeningBook(bookId = "book-1", title = "Book 1"),
                    createContinueListeningBook(bookId = "book-2", title = "Book 2"),
                )
            fixture.continueListeningFlow.value = books
            advanceUntilIdle()

            // Then - state should update reactively
            val ready = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isLoading)
            assertEquals(2, ready.continueListening.size)
            assertEquals("Book 1", ready.continueListening[0].title)
        }

    @Test
    fun `continueListening updates reactively when new book is played`() =
        runTest {
            // Given - start with one book
            val fixture = createFixture()
            fixture.continueListeningFlow.value =
                listOf(
                    createContinueListeningBook(bookId = "book-1", title = "Original Book"),
                )
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()
            assertEquals(
                1,
                assertIs<HomeUiState.Ready>(viewModel.state.value).continueListening.size,
            )

            // When - new book is played (Flow emits updated list)
            fixture.continueListeningFlow.value =
                listOf(
                    createContinueListeningBook(bookId = "book-new", title = "New Book"),
                    createContinueListeningBook(bookId = "book-1", title = "Original Book"),
                )
            advanceUntilIdle()

            // Then - UI updates immediately without manual refresh
            val ready = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertEquals(2, ready.continueListening.size)
            assertEquals("New Book", ready.continueListening[0].title)
        }

    // ========== User Observation Tests ==========

    @Test
    fun `observeUser updates userName from displayName`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()
            assertEquals("", assertIs<HomeUiState.Ready>(viewModel.state.value).userName)

            // When
            fixture.userFlow.value = createUser(displayName = "John Smith")
            advanceUntilIdle()

            // Then - first name extracted
            assertEquals("John", assertIs<HomeUiState.Ready>(viewModel.state.value).userName)
        }

    @Test
    fun `observeUser extracts first name only`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // When
            fixture.userFlow.value = createUser(displayName = "Jane Doe Johnson")
            advanceUntilIdle()

            // Then
            assertEquals("Jane", assertIs<HomeUiState.Ready>(viewModel.state.value).userName)
        }

    @Test
    fun `observeUser handles null user`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.userFlow.value = createUser(displayName = "John")
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()
            assertEquals("John", assertIs<HomeUiState.Ready>(viewModel.state.value).userName)

            // When
            fixture.userFlow.value = null
            advanceUntilIdle()

            // Then
            assertEquals("", assertIs<HomeUiState.Ready>(viewModel.state.value).userName)
        }

    @Test
    fun `observeUser handles blank displayName`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // When
            fixture.userFlow.value = createUser(displayName = "   ")
            advanceUntilIdle()

            // Then
            assertEquals("", assertIs<HomeUiState.Ready>(viewModel.state.value).userName)
        }

    @Test
    fun `observeUser handles single name`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // When
            fixture.userFlow.value = createUser(displayName = "Madonna")
            advanceUntilIdle()

            // Then
            assertEquals("Madonna", assertIs<HomeUiState.Ready>(viewModel.state.value).userName)
        }

    // ========== Refresh Tests ==========

    @Test
    fun `refresh triggers a full server sync`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.sync() } returns
                com.calypsan.listenup.client.core
                    .Success(Unit)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // When
            viewModel.refresh()
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.syncRepository.sync() }
        }

    @Test
    fun `refresh handles sync failure gracefully`() =
        runTest {
            // Given - sync returns a failure Result (not an exception)
            val fixture = createFixture()
            everySuspend { fixture.syncRepository.sync() } returns
                com.calypsan.listenup.client.core
                    .Failure(RuntimeException("Network error"))
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // When
            viewModel.refresh()
            advanceUntilIdle()

            // Then - ViewModel is still functional, sync was attempted
            verifySuspend { fixture.syncRepository.sync() }
            val ready = assertIs<HomeUiState.Ready>(viewModel.state.value)
            assertFalse(ready.isLoading)
        }

    // ========== State Derived Properties Tests ==========

    @Test
    fun `hasContinueListening is true when list not empty`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.continueListeningFlow.value = listOf(createContinueListeningBook())
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            assertTrue(assertIs<HomeUiState.Ready>(viewModel.state.value).hasContinueListening)
        }

    @Test
    fun `hasContinueListening is false when list empty`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.continueListeningFlow.value = emptyList()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            assertFalse(assertIs<HomeUiState.Ready>(viewModel.state.value).hasContinueListening)
        }

    @Test
    fun `greeting includes userName when available`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.currentHour = 10 // Morning
            fixture.userFlow.value = createUser(displayName = "Alice")
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            assertEquals(
                "Good morning, Alice",
                assertIs<HomeUiState.Ready>(viewModel.state.value).greeting,
            )
        }

    @Test
    fun `greeting without userName is time-only`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.currentHour = 14 // Afternoon
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            assertEquals(
                "Good afternoon",
                assertIs<HomeUiState.Ready>(viewModel.state.value).greeting,
            )
        }

    // ========== Sync State Tests ==========

    @Test
    fun `isSyncing reflects SyncState Syncing`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()
            assertFalse(assertIs<HomeUiState.Ready>(viewModel.state.value).isSyncing)

            // When
            fixture.syncStateFlow.value = SyncState.Syncing
            advanceUntilIdle()

            // Then
            assertTrue(assertIs<HomeUiState.Ready>(viewModel.state.value).isSyncing)
        }

    // ========== Time-Based Greeting Tests ==========

    @Test
    fun `greeting is morning between 5 and 11`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.currentHour = 8
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            assertEquals(
                "Good morning",
                assertIs<HomeUiState.Ready>(viewModel.state.value).greeting,
            )
        }

    @Test
    fun `greeting is afternoon between 12 and 16`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.currentHour = 14
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            assertEquals(
                "Good afternoon",
                assertIs<HomeUiState.Ready>(viewModel.state.value).greeting,
            )
        }

    @Test
    fun `greeting is evening between 17 and 20`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.currentHour = 19
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            assertEquals(
                "Good evening",
                assertIs<HomeUiState.Ready>(viewModel.state.value).greeting,
            )
        }

    @Test
    fun `greeting is night after 21`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.currentHour = 23
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            assertEquals(
                "Good night",
                assertIs<HomeUiState.Ready>(viewModel.state.value).greeting,
            )
        }

    @Test
    fun `greeting is night before 5`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.currentHour = 3
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            assertEquals(
                "Good night",
                assertIs<HomeUiState.Ready>(viewModel.state.value).greeting,
            )
        }

    // ========== Snackbar Tests ==========

    @Test
    fun `continue listening failure emits snackbar`() =
        runTest {
            val fixture = createFixture()
            every { fixture.homeRepository.observeContinueListening(any()) } returns
                flow { throw RuntimeException("boom") }
            val viewModel = fixture.build()

            val emitted = mutableListOf<String>()
            // Ordering matters: the snackbar collector must subscribe to the Channel-backed
            // Flow before state subscription triggers the catch { trySend(...) } so the
            // emission is routed to a live collector under runTest's virtual scheduler.
            backgroundScope.launch { viewModel.snackbarMessages.collect { emitted += it } }
            keepStateHot(viewModel)
            advanceUntilIdle()

            assertTrue(emitted.contains("Failed to load continue listening"))
        }

    @Test
    fun `pipeline failure surfaces Error state`() =
        runTest {
            // Make the combine transform itself throw by failing the greeting
            // computation. This isolates the failure to the outer pipeline (the
            // `init` block does not call currentHour), so the terminal `.catch` is
            // the only handler that sees it.
            val fixture = createFixture()
            // syncRepository stubs are installed by `build()`; install them here
            // since we bypass build() to inject the failing currentHour.
            every { fixture.syncRepository.scanProgress } returns fixture.scanProgressFlow
            every { fixture.syncRepository.syncState } returns fixture.syncStateFlow
            val viewModel =
                HomeViewModel(
                    homeRepository = fixture.homeRepository,
                    userRepository = fixture.userRepository,
                    shelfRepository = fixture.shelfRepository,
                    syncRepository = fixture.syncRepository,
                    currentHour = { throw RuntimeException("upstream boom") },
                )
            // Emit a non-null user so the combine pipeline's transform actually runs.
            fixture.userFlow.value = createUser()
            keepStateHot(viewModel)
            advanceUntilIdle()

            val state = viewModel.state.value
            val error = assertIs<HomeUiState.Error>(state)
            assertEquals("Failed to load home screen", error.message)
        }
}
