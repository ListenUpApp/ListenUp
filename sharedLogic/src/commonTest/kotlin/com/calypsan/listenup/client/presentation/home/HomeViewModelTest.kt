package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
class HomeViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        class TestFixture {
            val homeRepository: HomeRepository = mock()
            val userRepository: UserRepository = mock()
            val shelfRepository: ShelfRepository = mock()
            val syncRepository: SyncRepository = mock()
            val userFlow = MutableStateFlow<User?>(null)
            val continueListeningFlow = MutableStateFlow<List<ContinueListeningItem>>(emptyList())
            val scanProgressFlow =
                MutableStateFlow<com.calypsan.listenup.client.domain.model.ScanProgressState?>(null)
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

        fun createFixture(): TestFixture {
            val fixture = TestFixture()

            // Default stubs for reactive observation
            every { fixture.userRepository.observeCurrentUser() } returns fixture.userFlow
            every { fixture.homeRepository.observeContinueListening(any()) } returns
                fixture.continueListeningFlow
            every { fixture.shelfRepository.observeMyShelves(any()) } returns flowOf(emptyList())

            return fixture
        }

        fun TestScope.keepStateHot(viewModel: HomeViewModel) {
            backgroundScope.launch { viewModel.state.collect { } }
        }

        // ========== Test Data Factories ==========

        fun createUser(
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

        fun createContinueListeningBook(
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

        fun createReadyItem(
            bookId: String = "book-1",
            title: String = "Test Book",
            authorNames: String = "Test Author",
            progress: Float = 0.5f,
            currentPositionMs: Long = 1_800_000L,
            totalDurationMs: Long = 3_600_000L,
        ): ContinueListeningItem.Ready =
            ContinueListeningItem.Ready(
                bookId = bookId,
                book =
                    createContinueListeningBook(
                        bookId = bookId,
                        title = title,
                        authorNames = authorNames,
                        progress = progress,
                        currentPositionMs = currentPositionMs,
                        totalDurationMs = totalDurationMs,
                    ),
            )

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Initial State Tests ==========

        test("init starts observation and transitions to Ready after first emission") {
            runTest {
                // Given
                val fixture = createFixture()

                // When - viewModel created, stateIn pipeline subscribes on first collect
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then - state should be Ready after combine emits
                viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
            }
        }

        // ========== Reactive Observation Tests ==========

        test("observeContinueListening updates state when flow emits") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                val initial = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                initial.continueListening.isEmpty() shouldBe true

                // When - flow emits new data
                val items =
                    listOf(
                        createReadyItem(bookId = "book-1", title = "Book 1"),
                        createReadyItem(bookId = "book-2", title = "Book 2"),
                    )
                fixture.continueListeningFlow.value = items
                advanceUntilIdle()

                // Then - state should update reactively
                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.isLoading shouldBe false
                ready.continueListening.size shouldBe 2
                (ready.continueListening[0] as ContinueListeningItem.Ready).book.title shouldBe "Book 1"
            }
        }

        test("continueListening updates reactively when new book is played") {
            runTest {
                // Given - start with one book
                val fixture = createFixture()
                fixture.continueListeningFlow.value =
                    listOf(
                        createReadyItem(bookId = "book-1", title = "Original Book"),
                    )
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .continueListening.size shouldBe 1

                // When - new book is played (Flow emits updated list)
                fixture.continueListeningFlow.value =
                    listOf(
                        createReadyItem(bookId = "book-new", title = "New Book"),
                        createReadyItem(bookId = "book-1", title = "Original Book"),
                    )
                advanceUntilIdle()

                // Then - UI updates immediately without manual refresh
                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.continueListening.size shouldBe 2
                (ready.continueListening[0] as ContinueListeningItem.Ready).book.title shouldBe "New Book"
            }
        }

        // ========== User Observation Tests ==========

        test("observeUser updates userName from displayName") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .userName shouldBe ""

                // When
                fixture.userFlow.value = createUser(displayName = "John Smith")
                advanceUntilIdle()

                // Then - first name extracted
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .userName shouldBe "John"
            }
        }

        test("observeUser extracts first name only") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // When
                fixture.userFlow.value = createUser(displayName = "Jane Doe Johnson")
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .userName shouldBe "Jane"
            }
        }

        test("observeUser handles null user") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.userFlow.value = createUser(displayName = "John")
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .userName shouldBe "John"

                // When
                fixture.userFlow.value = null
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .userName shouldBe ""
            }
        }

        test("observeUser handles blank displayName") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // When
                fixture.userFlow.value = createUser(displayName = "   ")
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .userName shouldBe ""
            }
        }

        test("observeUser handles single name") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // When
                fixture.userFlow.value = createUser(displayName = "Madonna")
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .userName shouldBe "Madonna"
            }
        }

        // ========== Refresh Tests ==========

        test("refresh triggers a full server sync") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.syncRepository.sync() } returns
                    AppResult.Success(Unit)
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // When
                viewModel.refresh()
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.syncRepository.sync() }
            }
        }

        test("refresh handles sync failure gracefully") {
            runTest {
                // Given - sync returns a failure Result (not an exception)
                val fixture = createFixture()
                everySuspend { fixture.syncRepository.sync() } returns
                    Failure(RuntimeException("Network error"))
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // When
                viewModel.refresh()
                advanceUntilIdle()

                // Then - ViewModel is still functional, sync was attempted
                verifySuspend { fixture.syncRepository.sync() }
                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.isLoading shouldBe false
            }
        }

        // ========== State Derived Properties Tests ==========

        test("hasContinueListening is true when list not empty") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.continueListeningFlow.value = listOf(createReadyItem())
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .hasContinueListening shouldBe true
            }
        }

        test("hasContinueListening is false when list empty") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.continueListeningFlow.value = emptyList()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .hasContinueListening shouldBe false
            }
        }

        test("greeting includes userName when available") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 10 // Morning
                fixture.userFlow.value = createUser(displayName = "Alice")
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .greeting shouldBe
                    "Good morning, Alice"
            }
        }

        test("greeting without userName is time-only") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 14 // Afternoon
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .greeting shouldBe
                    "Good afternoon"
            }
        }

        // ========== Sync State Tests ==========

        test("isSyncing reflects SyncState Syncing") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .isSyncing shouldBe false

                // When
                fixture.syncStateFlow.value = SyncState.Syncing
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .isSyncing shouldBe true
            }
        }

        // ========== Time-Based Greeting Tests ==========

        test("greeting is morning between 5 and 11") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 8
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .greeting shouldBe
                    "Good morning"
            }
        }

        test("greeting is afternoon between 12 and 16") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 14
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .greeting shouldBe
                    "Good afternoon"
            }
        }

        test("greeting is evening between 17 and 20") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 19
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .greeting shouldBe
                    "Good evening"
            }
        }

        test("greeting is night after 21") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 23
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .greeting shouldBe
                    "Good night"
            }
        }

        test("greeting is night before 5") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 3
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .greeting shouldBe
                    "Good night"
            }
        }

        // ========== Snackbar Tests ==========

        test("continue listening failure emits snackbar") {
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

                emitted.contains("Failed to load continue listening") shouldBe true
            }
        }

        test("continue listening cancellation is not swallowed into a fallback emission") {
            runTest {
                // Regression: the continueListening .catch used to omit the cancellation
                // rethrow, swallowing CancellationException into an emit(emptyList()).
                // fallbackTo restores the rethrow by construction, so a cancelling upstream
                // must NOT collapse into a Ready/empty-list state — it must propagate.
                val fixture = createFixture()
                every { fixture.homeRepository.observeContinueListening(any()) } returns
                    flow { throw kotlin.coroutines.cancellation.CancellationException("cancelled") }
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // State never advanced past the stateIn initial value — the cancelling
                // upstream did not produce a Ready emission with an empty list.
                viewModel.state.value.shouldBeInstanceOf<HomeUiState.Loading>()
            }
        }

        test("pipeline failure surfaces Error state") {
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
                val error = state.shouldBeInstanceOf<HomeUiState.Error>()
                error.message shouldBe "Failed to load home screen"
            }
        }
    })
