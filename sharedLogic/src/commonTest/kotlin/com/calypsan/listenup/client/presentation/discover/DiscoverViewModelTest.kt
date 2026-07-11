package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.client.domain.model.ActiveSession
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DiscoveryBook
import com.calypsan.listenup.client.domain.repository.ShelfRepository
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError

/**
 * Tests for [DiscoverViewModel].
 *
 * Each of the four sections has its own `StateFlow<…UiState>` pipeline and is
 * exercised independently. All covered behaviours:
 * - Initial `Loading` before any subscription on `currentlyListeningState`
 * - `Ready` emissions from Room-backed flows (authenticated and unauthenticated
 *   branches for currently-listening)
 * - `Error` emissions when the upstream flow throws
 * - `discoverBooksState` initial random load via the refresh trigger and
 *   re-query on `refresh()`
 * - discover shelves: on-demand RPC load on init, grouped-by-owner Ready state,
 *   and Error state when the RPC fails
 *
 * Uses Mokkery for mocking all four repositories plus `AuthSession`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        // ========== Test Data Factories ==========

        fun createActiveSession(
            sessionId: String = "active-1",
            userId: String = OTHER_USER_ID,
            bookId: String = "book-1",
        ): ActiveSession =
            ActiveSession(
                sessionId = sessionId,
                userId = userId,
                bookId = bookId,
                startedAtMs = 0L,
                updatedAtMs = 0L,
                user =
                    ActiveSession.SessionUser(
                        displayName = "Reader",
                        avatarType = "initials",
                        avatarValue = null,
                        avatarColor = "#FF0000",
                    ),
                book =
                    ActiveSession.SessionBook(
                        id = bookId,
                        title = "Some Book",
                        coverPath = null,
                        coverBlurHash = null,
                        authorName = "An Author",
                    ),
            )

        fun createDiscoveryBook(
            id: String = "book-1",
            title: String = "A Book",
            coverHash: String? = null,
        ): DiscoveryBook =
            DiscoveryBook(
                id = id,
                title = title,
                authorName = "An Author",
                coverPath = null,
                coverBlurHash = null,
                coverHash = coverHash,
                createdAt = 0L,
            )

        fun createShelf(
            id: String = "shelf-1",
            ownerId: String = OTHER_USER_ID,
            ownerDisplayName: String = "Alice",
        ): Shelf =
            Shelf(
                id = ShelfId(id),
                name = "A Shelf",
                description = null,
                isPrivate = false,
                ownerId = ownerId,
                ownerDisplayName = ownerDisplayName,
                bookCount = 0,
                totalDurationSeconds = 0L,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )

        // ========== Test Fixture ==========

        class TestFixture {
            val bookRepository: BookRepository = mock()
            val activeSessionRepository: ActiveSessionRepository = mock()
            val authSession: AuthSession = mock()
            val shelfRepository: ShelfRepository = mock()

            val authStateFlow = MutableStateFlow<AuthState>(AuthState.Initializing)
            val activeSessionsFlow = MutableStateFlow<List<ActiveSession>>(emptyList())
            val recentlyAddedFlow = MutableStateFlow<List<DiscoveryBook>>(emptyList())
            val errorBus = ErrorBus()

            val openCampfiresFlow = MutableStateFlow<List<OpenCampfireSummary>>(emptyList())

            fun build(): DiscoverViewModel =
                DiscoverViewModel(
                    bookRepository = bookRepository,
                    activeSessionRepository = activeSessionRepository,
                    authSession = authSession,
                    shelfRepository = shelfRepository,
                    errorBus = errorBus,
                    openCampfires = openCampfiresFlow,
                )
        }

        fun createFixture(
            authState: AuthState = AuthState.Authenticated(userId = UserId(USER_ID), sessionId = SessionId(SESSION_ID)),
            discoveredShelves: List<Shelf> = emptyList(),
            randomBooks: List<DiscoveryBook> = emptyList(),
        ): TestFixture {
            val fixture = TestFixture()
            fixture.authStateFlow.value = authState

            every { fixture.authSession.authState } returns fixture.authStateFlow
            every { fixture.activeSessionRepository.observeActiveSessions(any()) } returns fixture.activeSessionsFlow
            every { fixture.bookRepository.observeRecentlyAddedBooks(any()) } returns fixture.recentlyAddedFlow
            every { fixture.bookRepository.observeRandomUnstartedBooks(any()) } returns flowOf(randomBooks)
            everySuspend { fixture.shelfRepository.discoverShelves() } returns AppResult.Success(discoveredShelves)

            return fixture
        }

        fun TestScope.keepStateHot(flow: StateFlow<*>) {
            backgroundScope.launch { flow.collect { } }
        }

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Currently Listening Tests ==========

        test("currentlyListeningState initial value is Loading before subscription") {
            runTest {
                // Given
                val fixture = createFixture()

                // When - viewModel created; `stateIn` initialValue is Loading.
                // Do NOT start collecting — the stateIn initialValue is what we assert.
                val viewModel = fixture.build()

                // Then
                viewModel.currentlyListeningState.value.shouldBeInstanceOf<CurrentlyListeningUiState.Loading>()
            }
        }

        test("currentlyListeningState becomes Ready when authenticated and flow emits") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.activeSessionsFlow.value = listOf(createActiveSession(sessionId = "s-1"))

                // When
                val viewModel = fixture.build().also { keepStateHot(it.currentlyListeningState) }
                advanceUntilIdle()

                // Then
                val ready = viewModel.currentlyListeningState.value.shouldBeInstanceOf<CurrentlyListeningUiState.Ready>()
                ready.sessions.size shouldBe 1
                ready.sessions.first().sessionId shouldBe "s-1"
            }
        }

        test("currentlyListeningState becomes Ready empty when unauthenticated") {
            runTest {
                // Given - unauthenticated auth state steers flatMapLatest to flowOf(emptyList())
                val fixture = createFixture(authState = AuthState.NeedsLogin())

                // When
                val viewModel = fixture.build().also { keepStateHot(it.currentlyListeningState) }
                advanceUntilIdle()

                // Then
                val ready = viewModel.currentlyListeningState.value.shouldBeInstanceOf<CurrentlyListeningUiState.Ready>()
                ready.isEmpty shouldBe true
            }
        }

        test("currentlyListeningState becomes Error when upstream throws") {
            runTest {
                // Given - observeActiveSessions throws on collection
                val fixture = createFixture()
                every { fixture.activeSessionRepository.observeActiveSessions(any()) } returns
                    flow { throw RuntimeException("boom") }

                // When
                val viewModel = fixture.build().also { keepStateHot(it.currentlyListeningState) }
                advanceUntilIdle()

                // Then
                val err = viewModel.currentlyListeningState.value.shouldBeInstanceOf<CurrentlyListeningUiState.Error>()
                err.message shouldBe "Failed to load currently listening"
            }
        }

        // ========== Live Campfires ("Live now") Tests ==========

        test("liveCampfiresState reflects the open-campfires flow") {
            runTest {
                // Given
                val fixture = createFixture()
                val summary =
                    OpenCampfireSummary(
                        id = CampfireId("cf-1"),
                        bookId = "book-1",
                        phase = CampfirePhase.LIVE,
                        name = "Campfire",
                        hostUserId = "host-1",
                        memberCount = 2,
                        controlMode = CampfireControlMode.EVERYONE,
                        inviteOnly = false,
                    )

                // When
                val viewModel = fixture.build().also { keepStateHot(it.liveCampfiresState) }
                advanceUntilIdle()
                fixture.openCampfiresFlow.value = listOf(summary)
                advanceUntilIdle()

                // Then
                viewModel.liveCampfiresState.value.map { it.id.value } shouldBe listOf("cf-1")
            }
        }

        // ========== Recently Added Tests ==========

        test("recentlyAddedState becomes Ready when flow emits") {
            runTest {
                // Given
                val fixture = createFixture()
                fixture.recentlyAddedFlow.value = listOf(createDiscoveryBook(id = "new-1", title = "New"))

                // When
                val viewModel = fixture.build().also { keepStateHot(it.recentlyAddedState) }
                advanceUntilIdle()

                // Then
                val ready = viewModel.recentlyAddedState.value.shouldBeInstanceOf<RecentlyAddedUiState.Ready>()
                ready.books.size shouldBe 1
                ready.books.first().id shouldBe "new-1"
            }
        }

        test("recentlyAddedState carries coverHash into RecentlyAddedUiBook") {
            runTest {
                // Given - a recently-added book with a non-null coverHash
                val fixture = createFixture()
                fixture.recentlyAddedFlow.value =
                    listOf(createDiscoveryBook(id = "new-1", title = "New", coverHash = "abc123"))

                // When
                val viewModel = fixture.build().also { keepStateHot(it.recentlyAddedState) }
                advanceUntilIdle()

                // Then - coverHash survives the DiscoveryBook → RecentlyAddedUiBook mapping
                val ready = viewModel.recentlyAddedState.value.shouldBeInstanceOf<RecentlyAddedUiState.Ready>()
                ready.books.first().coverHash shouldBe "abc123"
            }
        }

        test("recentlyAddedState becomes Error when upstream throws") {
            runTest {
                // Given
                val fixture = createFixture()
                every { fixture.bookRepository.observeRecentlyAddedBooks(any()) } returns
                    flow { throw RuntimeException("boom") }

                // When
                val viewModel = fixture.build().also { keepStateHot(it.recentlyAddedState) }
                advanceUntilIdle()

                // Then
                val err = viewModel.recentlyAddedState.value.shouldBeInstanceOf<RecentlyAddedUiState.Error>()
                err.message shouldBe "Failed to load recently added"
            }
        }

        // ========== Discover Shelves Tests ==========

        test("discoverShelvesState becomes Ready grouped by owner") {
            runTest {
                // Given - two shelves from the same owner, one from another owner
                val fixture =
                    createFixture(
                        discoveredShelves =
                            listOf(
                                createShelf(id = "s1", ownerId = "alice", ownerDisplayName = "Alice"),
                                createShelf(id = "s2", ownerId = "alice", ownerDisplayName = "Alice"),
                                createShelf(id = "s3", ownerId = "bob", ownerDisplayName = "Bob"),
                            ),
                    )

                // When
                val viewModel = fixture.build().also { keepStateHot(it.discoverShelvesState) }
                advanceUntilIdle()

                // Then
                val ready = viewModel.discoverShelvesState.value.shouldBeInstanceOf<DiscoverShelvesUiState.Ready>()
                ready.users.size shouldBe 2
                ready.totalShelfCount shouldBe 3
                val alice = ready.users.single { it.user.id == "alice" }
                alice.shelves.size shouldBe 2
            }
        }

        test("discoverShelvesState becomes Error when the discover RPC fails") {
            runTest {
                // Given - the discover RPC returns a failure
                val fixture = createFixture()
                everySuspend { fixture.shelfRepository.discoverShelves() } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "boom"),
                    )

                // When
                val viewModel = fixture.build().also { keepStateHot(it.discoverShelvesState) }
                advanceUntilIdle()

                // Then
                val err = viewModel.discoverShelvesState.value.shouldBeInstanceOf<DiscoverShelvesUiState.Error>()
                err.message shouldBe "Failed to load discover shelves"
            }
        }

        test("connectivity failure on discover shelves does NOT emit to the global errorBus") {
            runTest {
                // Given - offline: the discover RPC fails with a connectivity error
                val fixture = createFixture()
                everySuspend { fixture.shelfRepository.discoverShelves() } returns
                    AppResult.Failure(TransportError.NetworkUnavailable())

                // Subscribe to the bus BEFORE init runs (SharedFlow has no replay)
                val emitted = mutableListOf<AppError>()
                backgroundScope.launch { fixture.errorBus.errors.collect { emitted += it } }
                advanceUntilIdle()

                // When - init triggers loadDiscoverShelves
                val viewModel = fixture.build().also { keepStateHot(it.discoverShelvesState) }
                advanceUntilIdle()

                // Then - no global snackbar flash; the section degrades to its local Error state
                emitted shouldBe emptyList()
                viewModel.discoverShelvesState.value.shouldBeInstanceOf<DiscoverShelvesUiState.Error>()
            }
        }

        test("timeout failure on discover shelves does NOT emit to the global errorBus") {
            runTest {
                // Given - a connection timeout (the second connectivity subtype)
                val fixture = createFixture()
                everySuspend { fixture.shelfRepository.discoverShelves() } returns
                    AppResult.Failure(TransportError.Timeout())

                val emitted = mutableListOf<AppError>()
                backgroundScope.launch { fixture.errorBus.errors.collect { emitted += it } }
                advanceUntilIdle()

                // When
                fixture.build().also { keepStateHot(it.discoverShelvesState) }
                advanceUntilIdle()

                // Then - timeouts are suppressed from the global snackbar too
                emitted shouldBe emptyList()
            }
        }

        test("non-connectivity failure on discover shelves still emits to the global errorBus") {
            runTest {
                // Given - a genuine server error (not connectivity)
                val fixture = createFixture()
                val serverError = TransportError.Server5xx(statusCode = 500)
                everySuspend { fixture.shelfRepository.discoverShelves() } returns
                    AppResult.Failure(serverError)

                val emitted = mutableListOf<AppError>()
                backgroundScope.launch { fixture.errorBus.errors.collect { emitted += it } }
                advanceUntilIdle()

                // When
                fixture.build().also { keepStateHot(it.discoverShelvesState) }
                advanceUntilIdle()

                // Then - real errors still surface globally
                emitted shouldBe listOf(serverError)
            }
        }

        // ========== Discover Books Tests ==========

        test("discoverBooksState becomes Ready with random books on initial load") {
            runTest {
                // Given
                val fixture = createFixture(randomBooks = listOf(createDiscoveryBook(id = "r-1")))

                // When
                val viewModel = fixture.build().also { keepStateHot(it.discoverBooksState) }
                advanceUntilIdle()

                // Then
                val ready = viewModel.discoverBooksState.value.shouldBeInstanceOf<DiscoverBooksUiState.Ready>()
                ready.books.size shouldBe 1
                ready.books.first().id shouldBe "r-1"
            }
        }

        test("discoverBooksState reloads when refresh is called") {
            runTest {
                // Given
                val fixture = createFixture(randomBooks = listOf(createDiscoveryBook(id = "r-1")))
                val viewModel = fixture.build().also { keepStateHot(it.discoverBooksState) }
                advanceUntilIdle()

                // When
                viewModel.refresh()
                advanceUntilIdle()

                // Then - random books query invoked twice: initial subscription + refresh trigger bump.
                // Mokkery's default VerifyMode is `exactly(1)`, so assert `atLeast(2)` explicitly.
                verifySuspend(
                    dev.mokkery.verify.VerifyMode
                        .atLeast(2),
                ) {
                    fixture.bookRepository.observeRandomUnstartedBooks(any())
                }
            }
        }

        // ========== Discover RPC Load Tests ==========

        test("discover shelves are loaded on init when authenticated") {
            runTest {
                // Given
                val fixture = createFixture()

                // When - init runs
                fixture.build()
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.shelfRepository.discoverShelves() }
            }
        }

        test("discover shelves are not loaded when unauthenticated") {
            runTest {
                // Given - unauthenticated
                val fixture = createFixture(authState = AuthState.NeedsLogin())

                // When
                fixture.build()
                advanceUntilIdle()

                // Then
                verifySuspend(dev.mokkery.verify.VerifyMode.not) {
                    fixture.shelfRepository.discoverShelves()
                }
            }
        }

        test("refresh re-fetches discover shelves") {
            runTest {
                // Given
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.refresh()
                advanceUntilIdle()

                // Then - once on init, once on refresh
                verifySuspend(
                    dev.mokkery.verify.VerifyMode
                        .atLeast(2),
                ) {
                    fixture.shelfRepository.discoverShelves()
                }
            }
        }
    })

private const val USER_ID = "user-1"
private const val SESSION_ID = "session-1"
private const val OTHER_USER_ID = "user-2"
