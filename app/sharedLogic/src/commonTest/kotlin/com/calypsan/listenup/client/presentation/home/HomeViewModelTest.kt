package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.test.SimulatedFailure
import com.calypsan.listenup.client.test.fake.FakeNetworkMonitor
import com.calypsan.listenup.client.test.fake.FakeServerReachability
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * - The first name and the part of the day (each platform words the greeting)
 * - State derived properties
 * - The slot, typed errors and the finish-card actions
 *
 * Uses Mokkery for mocking repositories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        class TestFixture(
            failWrites: Boolean = false,
        ) {
            val homeRepository: HomeRepository = mock()
            val userRepository: UserRepository = mock()
            val shelfRepository: ShelfRepository = mock()
            val syncRepository: SyncRepository = mock()
            val userFlow = MutableStateFlow<User?>(null)
            val continueListeningFlow = MutableStateFlow<List<ContinueListeningItem>>(emptyList())
            val scanProgressFlow =
                MutableStateFlow<com.calypsan.listenup.client.domain.model.ScanProgressState?>(null)
            val syncStateFlow = MutableStateFlow<SyncState>(SyncState.Idle)
            val buildingInitialLibraryFlow = MutableStateFlow(false)
            val firstCatchUpFlow = MutableStateFlow(false)
            var currentHour: Int = 10 // Default to morning
            val rig = HomeSlotRig(failWrites = failWrites)
            val reachability = FakeServerReachability()
            val network = FakeNetworkMonitor()
            val errorBus = ErrorBus()

            fun build(): HomeViewModel {
                every { syncRepository.scanProgress } returns scanProgressFlow
                every { syncRepository.syncState } returns syncStateFlow
                every { syncRepository.buildingInitialLibrary } returns buildingInitialLibraryFlow
                every { syncRepository.firstCatchUpRunning } returns firstCatchUpFlow
                return HomeViewModel(
                    homeRepository = homeRepository,
                    userRepository = userRepository,
                    shelfRepository = shelfRepository,
                    syncRepository = syncRepository,
                    slotPresenter = rig.presenter(),
                    serverReachability = reachability,
                    networkMonitor = network,
                    errorBus = errorBus,
                    currentHour = { currentHour },
                )
            }
        }

        fun createFixture(failWrites: Boolean = false): TestFixture {
            val fixture = TestFixture(failWrites)

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
                createdAtMs = 1_704_067_200_000L,
                updatedAtMs = 1_704_067_200_000L,
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
                narratorNames = null,
                lastPlayedAtMs = 1_704_067_200_000L,
                isDownloaded = false,
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
                // Given - a loaded book that is not in the list, so the slot is Absent and the rail is the whole list
                val fixture = createFixture()
                fixture.rig.loaded.value = LoadedBook.Loaded("elsewhere", isPlaying = false, isFinished = false)
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                val initial = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                initial.everythingInProgress.isEmpty() shouldBe true

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
                ready.everythingInProgress.size shouldBe 2
                (ready.everythingInProgress[0] as ContinueListeningItem.Ready).book.title shouldBe "Book 1"
            }
        }

        test("Everything in progress updates reactively when a new book is played") {
            runTest {
                // Given - start with one book, and a loaded book that is not in the list
                val fixture = createFixture()
                fixture.rig.loaded.value = LoadedBook.Loaded("elsewhere", isPlaying = false, isFinished = false)
                fixture.continueListeningFlow.value =
                    listOf(
                        createReadyItem(bookId = "book-1", title = "Original Book"),
                    )
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .everythingInProgress.size shouldBe 1

                // When - new book is played (Flow emits updated list)
                fixture.continueListeningFlow.value =
                    listOf(
                        createReadyItem(bookId = "book-new", title = "New Book"),
                        createReadyItem(bookId = "book-1", title = "Original Book"),
                    )
                advanceUntilIdle()

                // Then - UI updates immediately without manual refresh
                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.everythingInProgress.size shouldBe 2
                (ready.everythingInProgress[0] as ContinueListeningItem.Ready).book.title shouldBe "New Book"
            }
        }

        // ========== User Observation Tests ==========

        test("observeUser updates firstName from displayName") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .firstName shouldBe null

                // When
                fixture.userFlow.value = createUser(displayName = "John Smith")
                advanceUntilIdle()

                // Then - first name extracted
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .firstName shouldBe "John"
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
                    .firstName shouldBe "Jane"
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
                    .firstName shouldBe "John"

                // When
                fixture.userFlow.value = null
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .firstName shouldBe null
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
                    .firstName shouldBe null
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
                    .firstName shouldBe "Madonna"
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

        test("Home recovers from Error when refresh is called") {
            // ⛔ The regression: the fallback sat OUTSIDE any restartable run, so the Error ended the
            // flow. refresh() re-synced Room underneath a screen that could no longer hear it, and
            // iOS's "Try again" did nothing at all.
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.syncRepository.sync() } returns AppResult.Success(Unit)
                var collections = 0
                every { fixture.userRepository.observeCurrentUser() } returns
                    flow {
                        collections++
                        if (collections == 1) throw SimulatedFailure("transient")
                        emit(createUser(displayName = "Jane Doe"))
                    }
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value.shouldBeInstanceOf<HomeUiState.Error>()

                viewModel.refresh()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.firstName shouldBe "Jane"
            }
        }

        test("a failed Continue Listening row comes back when refresh is called") {
            // Continue Listening falls back to an empty row (plus a typed error) rather than failing
            // the screen — and that fallback, too, used to be the row's last word.
            runTest {
                val fixture = createFixture()
                fixture.rig.loaded.value = LoadedBook.Loaded("elsewhere", isPlaying = false, isFinished = false)
                everySuspend { fixture.syncRepository.sync() } returns AppResult.Success(Unit)
                var collections = 0
                every { fixture.homeRepository.observeContinueListening(any()) } returns
                    flow {
                        collections++
                        if (collections == 1) throw SimulatedFailure("transient")
                        emit(listOf(createReadyItem(bookId = "book-1")))
                    }
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .everythingInProgress
                    .isEmpty() shouldBe true

                viewModel.refresh()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.everythingInProgress.map { it.bookId } shouldBe listOf("book-1")
            }
        }

        test("refresh leaves a healthy Home pipeline alone") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.syncRepository.sync() } returns AppResult.Success(Unit)
                var collections = 0
                every { fixture.userRepository.observeCurrentUser() } returns
                    flow {
                        collections++
                        emit(createUser())
                    }
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                // The user stream feeds two branches of the pipeline, so count from here.
                val healthyCollections = collections

                viewModel.refresh()
                advanceUntilIdle()

                collections shouldBe healthyCollections
            }
        }

        // ========== State Derived Properties Tests ==========

        test("the first name and the part of the day are separate fields") {
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
                    .let { ready ->
                        ready.timeOfDay shouldBe TimeOfDay.Morning
                        ready.firstName shouldBe "Alice"
                    }
            }
        }

        test("no name leaves firstName null and the part of the day intact") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 14 // Afternoon
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .let { ready ->
                        ready.timeOfDay shouldBe TimeOfDay.Afternoon
                        ready.firstName shouldBe null
                    }
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

        test("isBuildingInitialLibrary reflects the repository, independently of isSyncing") {
            runTest {
                // A first seed keeps the socket `Connected`, so `isSyncing` stays FALSE throughout
                // it. Anything explaining a short shelf has to read this field instead — the exact
                // trap LibraryUiState.Loaded documents. Pinned here so the two cannot be conflated.
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                fixture.buildingInitialLibraryFlow.value = true
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.isBuildingInitialLibrary shouldBe true
                ready.isSyncing shouldBe false
            }
        }

        // ========== Time-Based Greeting Tests ==========

        test("8 o'clock is morning") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 8
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .timeOfDay shouldBe TimeOfDay.Morning
            }
        }

        test("14 o'clock is afternoon") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 14
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .timeOfDay shouldBe TimeOfDay.Afternoon
            }
        }

        test("19 o'clock is evening") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 19
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .timeOfDay shouldBe TimeOfDay.Evening
            }
        }

        test("23 o'clock is night") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 23
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .timeOfDay shouldBe TimeOfDay.Night
            }
        }

        test("3 o'clock is night") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.currentHour = 3
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                // Then
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .timeOfDay shouldBe TimeOfDay.Night
            }
        }

        test("no Ready while the loaded book is still restoring") {
            runTest {
                val fixture = createFixture()
                fixture.rig.loaded.value = LoadedBook.Restoring
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value.shouldBeInstanceOf<HomeUiState.Loading>()

                fixture.rig.loaded.value = LoadedBook.NothingLoaded
                advanceUntilIdle()
                viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
            }
        }

        test("Ready carries the slot, the rail, the time of day and the first name") {
            runTest {
                val fixture = createFixture()
                fixture.userFlow.value = createUser(displayName = "John Smith")
                fixture.continueListeningFlow.value = listOf(createReadyItem(bookId = "a"), createReadyItem(bookId = "b"))
                fixture.rig.play("a", hoursAgo = 1)
                fixture.rig.play("b", hoursAgo = 2)
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.slot shouldBe HomeSlot.Absent
                ready.everythingInProgress.map { it.bookId } shouldBe listOf("a", "b")
                ready.timeOfDay shouldBe TimeOfDay.Morning
                ready.firstName shouldBe "John"
                ready.connectivity shouldBe HomeConnectivity.Online
                ready.continueListeningError shouldBe null
            }
        }

        test("a loaded book leaves no slot and leads the rail") {
            runTest {
                val fixture = createFixture()
                fixture.continueListeningFlow.value = listOf(createReadyItem(bookId = "a"), createReadyItem(bookId = "b"))
                fixture.rig.loaded.value = LoadedBook.Loaded("b", isPlaying = false, isFinished = false)
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.slot shouldBe HomeSlot.Absent
                ready.everythingInProgress.map { it.bookId } shouldBe listOf("b", "a")
            }
        }

        test("nothing loaded and one book in progress: no hero, Everything in progress holds it") {
            runTest {
                val fixture = createFixture()
                fixture.continueListeningFlow.value = listOf(createReadyItem(bookId = "a"))
                fixture.rig.play("a", hoursAgo = 1)
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.slot shouldBe HomeSlot.Absent
                ready.everythingInProgress.map { it.bookId } shouldBe listOf("a")
            }
        }

        test("refresh is a decision point: a finish that landed meanwhile takes the slot") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.syncRepository.sync() } returns AppResult.Success(Unit)
                fixture.continueListeningFlow.value = listOf(createReadyItem(bookId = "a"))
                fixture.rig.play("a", hoursAgo = 5)
                fixture.rig.books.setBooks(listOf(TestData.bookListItem(id = "done")))
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                fixture.rig.finish("done", hoursAgo = 1)
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .slot shouldBe HomeSlot.Absent

                viewModel.refresh()
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .slot
                    .shouldBeInstanceOf<HomeSlot.JustFinished>()
                    .card.book.bookId shouldBe "done"
            }
        }

        test("dismissFinished retires the card at once and records the dismissal") {
            runTest {
                val fixture = createFixture()
                fixture.rig.finish("done", hoursAgo = 3)
                fixture.rig.books.setBooks(listOf(TestData.bookListItem(id = "done")))
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .slot
                    .shouldBeInstanceOf<HomeSlot.JustFinished>()

                viewModel.dismissFinished("done")
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .slot
                    .shouldBeInstanceOf<HomeSlot.NothingInProgress>()
                fixture.rig.positions
                    .observe(BookId("done"))
                    .first()
                    .shouldNotBeNull()
                    .finishedCardDismissedAtMs shouldBe RIG_NOW
            }
        }

        test("a dismissal that cannot be saved brings the card back and says why") {
            runTest {
                val fixture = createFixture(failWrites = true)
                fixture.rig.finish("done", hoursAgo = 3)
                fixture.rig.books.setBooks(listOf(TestData.bookListItem(id = "done")))
                val viewModel = fixture.build().also { keepStateHot(it) }
                val errors = mutableListOf<AppError>()
                // Unconfined, so the bus has its subscriber before anything emits (the bus does not replay).
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.errorBus.errors.collect { errors += it } }
                advanceUntilIdle()

                viewModel.dismissFinished("done")
                advanceUntilIdle()

                errors.single().shouldBeInstanceOf<UnexpectedClientError>()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .slot
                    .shouldBeInstanceOf<HomeSlot.JustFinished>()
            }
        }

        test("rateFinished writes through; a failure goes to the error bus") {
            runTest {
                val fixture = createFixture()
                fixture.rig.finish("done", hoursAgo = 3)
                val viewModel = fixture.build().also { keepStateHot(it) }
                val errors = mutableListOf<AppError>()
                // Unconfined, so the bus has its subscriber before anything emits (the bus does not replay).
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.errorBus.errors.collect { errors += it } }
                advanceUntilIdle()

                viewModel.rateFinished("done", halfStars = 8)
                advanceUntilIdle()
                verifySuspend { fixture.rig.ratings.rate("done", 8, null) }
                errors shouldBe emptyList()

                fixture.rig.rateResult = AppResult.Failure(UnexpectedClientError())
                viewModel.rateFinished("done", halfStars = 4)
                advanceUntilIdle()
                errors.single().shouldBeInstanceOf<UnexpectedClientError>()
            }
        }

        test("connectivity follows the device's network, then the server") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                fixture.reachability.state.value = Reachability.Unreachable
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .connectivity shouldBe
                    HomeConnectivity.ServerUnreachable

                fixture.network.onlineFlow.value = false
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .connectivity shouldBe HomeConnectivity.Offline
            }
        }

        // ========== Slot, errors and actions ==========

        test("a Continue Listening failure is Ready.continueListeningError, and the rest of Home stays up") {
            runTest {
                val fixture = createFixture()
                every { fixture.homeRepository.observeContinueListening(any()) } returns
                    flow { throw SimulatedFailure("boom") }
                fixture.userFlow.value = createUser(displayName = "Jane Doe")
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.continueListeningError shouldBe ErrorMapper.map(SimulatedFailure("boom"))
                ready.firstName shouldBe "Jane"
            }
        }

        test("a failed Continue Listening never reads as a new listener or nothing in progress: the slot is the failure") {
            runTest {
                val fixture = createFixture()
                every { fixture.homeRepository.observeContinueListening(any()) } returns
                    flow { throw SimulatedFailure("boom") }
                fixture.rig.finish("old", hoursAgo = 24 * 30)
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.slot shouldBe HomeSlot.Absent
                ready.everythingInProgress shouldBe emptyList()
            }
        }

        test("with no history at all, a failed Continue Listening is still not the welcome") {
            runTest {
                val fixture = createFixture()
                every { fixture.homeRepository.observeContinueListening(any()) } returns
                    flow { throw SimulatedFailure("boom") }
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .slot shouldBe HomeSlot.Absent
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
                // The user read sits inside the pipeline's combine, so its failure reaches the terminal fallback.
                val fixture = createFixture()
                every { fixture.userRepository.observeCurrentUser() } returns
                    flow { throw SimulatedFailure("upstream boom") }
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Error>()
                error.error shouldBe ErrorMapper.map(SimulatedFailure("upstream boom"))
            }
        }

        // ========== Arriving, the error greeting, retry, an offline rating, a remote dismissal ==========

        test("a fresh device's first catch-up shows first sync while positions arrive before their books") {
            runTest {
                val fixture = createFixture()
                fixture.firstCatchUpFlow.value = true
                fixture.continueListeningFlow.value = listOf(ContinueListeningItem.Loading(bookId = "wok"))
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.slot shouldBe HomeSlot.FirstSync
                ready.isLibraryArriving shouldBe true
                ready.isBuildingInitialLibrary shouldBe false
                ready.isSyncing shouldBe false // the socket is Connected for the whole seed
            }
        }

        test("while the library is arriving, no shelves yet reads as loading, never as the empty invitation") {
            runTest {
                val fixture = createFixture()
                fixture.firstCatchUpFlow.value = true
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .showsShelvesLoading shouldBe true

                fixture.firstCatchUpFlow.value = false
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .showsShelvesLoading shouldBe false
            }
        }

        test("first sync resolves in place when the book lands and the catch-up ends: it is in the rail") {
            runTest {
                val fixture = createFixture()
                fixture.firstCatchUpFlow.value = true
                fixture.continueListeningFlow.value = listOf(ContinueListeningItem.Loading(bookId = "wok"))
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .slot shouldBe HomeSlot.FirstSync

                fixture.rig.play("wok", hoursAgo = 1)
                fixture.continueListeningFlow.value = listOf(readyItem("wok"))
                fixture.firstCatchUpFlow.value = false
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<HomeUiState.Ready>()
                ready.slot shouldBe HomeSlot.Absent
                ready.everythingInProgress.map { it.bookId } shouldBe listOf("wok")
                ready.isLibraryArriving shouldBe false
            }
        }

        test("a page error keeps the part of the day, so the greeting stays") {
            runTest {
                val fixture = createFixture()
                fixture.currentHour = 19
                every { fixture.userRepository.observeCurrentUser() } returns flow { throw SimulatedFailure("boom") }
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Error>()
                    .timeOfDay shouldBe TimeOfDay.Evening
            }
        }

        test("retryConnection asks the server reachability to retry") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()

                viewModel.retryConnection()
                advanceUntilIdle()

                fixture.reachability.retryCalls shouldBe 1
            }
        }

        test("a rating made with the network down is accepted and reaches the rating repository (it queues)") {
            runTest {
                val fixture = createFixture()
                fixture.network.onlineFlow.value = false
                fixture.rig.finish("wok", hoursAgo = 1)
                val viewModel = fixture.build().also { keepStateHot(it) }
                val errors = mutableListOf<AppError>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.errorBus.errors.collect { errors += it } }
                advanceUntilIdle()

                viewModel.rateFinished("wok", halfStars = 7)
                advanceUntilIdle()

                verifySuspend { fixture.rig.ratings.rate("wok", 7, null) }
                errors shouldBe emptyList()
            }
        }

        test("a dismissal that arrives from another device ends the card at the next decision point") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.syncRepository.sync() } returns AppResult.Success(Unit)
                fixture.rig.finish("wok", hoursAgo = 1)
                fixture.rig.books.setBooks(listOf(TestData.bookListItem(id = "wok")))
                val viewModel = fixture.build().also { keepStateHot(it) }
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .slot
                    .shouldBeInstanceOf<HomeSlot.JustFinished>()

                // The other device's dismissal lands through sync, as a position row carrying it.
                val finished =
                    fixture.rig.positions
                        .observe(BookId("wok"))
                        .first()
                        .shouldNotBeNull()
                fixture.rig.positions.setPosition(finished.copy(finishedCardDismissedAtMs = RIG_NOW))
                viewModel.refresh()
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<HomeUiState.Ready>()
                    .slot
                    .shouldBeInstanceOf<HomeSlot.NothingInProgress>()
            }
        }
    })
