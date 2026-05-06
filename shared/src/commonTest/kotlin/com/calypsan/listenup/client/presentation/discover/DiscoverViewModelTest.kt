package com.calypsan.listenup.client.presentation.discover

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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.calypsan.listenup.client.core.error.ErrorBus

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
 * - `fetchInitialShelvesIfNeeded` gate: fetches when Room is empty, skips when
 *   populated
 *
 * Uses Mokkery for mocking all four repositories plus `AuthSession`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixture ==========

    private class TestFixture {
        val bookRepository: BookRepository = mock()
        val activeSessionRepository: ActiveSessionRepository = mock()
        val authSession: AuthSession = mock()
        val shelfRepository: ShelfRepository = mock()

        val authStateFlow = MutableStateFlow<AuthState>(AuthState.Initializing)
        val activeSessionsFlow = MutableStateFlow<List<ActiveSession>>(emptyList())
        val recentlyAddedFlow = MutableStateFlow<List<DiscoveryBook>>(emptyList())
        val discoverShelvesFlow = MutableStateFlow<List<Shelf>>(emptyList())

        fun build(): DiscoverViewModel =
            DiscoverViewModel(
                bookRepository = bookRepository,
                activeSessionRepository = activeSessionRepository,
                authSession = authSession,
                shelfRepository = shelfRepository,
                errorBus = ErrorBus(),
            )
    }

    private fun createFixture(
        authState: AuthState = AuthState.Authenticated(userId = UserId(USER_ID), sessionId = SessionId(SESSION_ID)),
        existingDiscoverShelfCount: Int = 1,
        randomBooks: List<DiscoveryBook> = emptyList(),
    ): TestFixture {
        val fixture = TestFixture()
        fixture.authStateFlow.value = authState

        every { fixture.authSession.authState } returns fixture.authStateFlow
        every { fixture.activeSessionRepository.observeActiveSessions(any()) } returns fixture.activeSessionsFlow
        every { fixture.bookRepository.observeRecentlyAddedBooks(any()) } returns fixture.recentlyAddedFlow
        every { fixture.bookRepository.observeRandomUnstartedBooks(any()) } returns flowOf(randomBooks)
        every { fixture.shelfRepository.observeDiscoverShelves(any()) } returns fixture.discoverShelvesFlow
        everySuspend { fixture.shelfRepository.countDiscoverShelves(any()) } returns existingDiscoverShelfCount
        everySuspend { fixture.shelfRepository.fetchAndCacheDiscoverShelves() } returns 0

        return fixture
    }

    private fun TestScope.keepStateHot(flow: StateFlow<*>) {
        backgroundScope.launch { flow.collect { } }
    }

    // ========== Test Data Factories ==========

    companion object {
        private const val USER_ID = "user-1"
        private const val SESSION_ID = "session-1"
        private const val OTHER_USER_ID = "user-2"

        private fun createActiveSession(
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

        private fun createDiscoveryBook(
            id: String = "book-1",
            title: String = "A Book",
        ): DiscoveryBook =
            DiscoveryBook(
                id = id,
                title = title,
                authorName = "An Author",
                coverPath = null,
                coverBlurHash = null,
                createdAt = 0L,
            )

        private fun createShelf(
            id: String = "shelf-1",
            ownerId: String = OTHER_USER_ID,
            ownerDisplayName: String = "Alice",
        ): Shelf =
            Shelf(
                id = id,
                name = "A Shelf",
                description = null,
                ownerId = ownerId,
                ownerDisplayName = ownerDisplayName,
                ownerAvatarColor = "#00FF00",
                bookCount = 0,
                totalDurationSeconds = 0L,
                createdAtMs = 0L,
                updatedAtMs = 0L,
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

    // ========== Currently Listening Tests ==========

    @Test
    fun `currentlyListeningState initial value is Loading before subscription`() =
        runTest {
            // Given
            val fixture = createFixture()

            // When - viewModel created; `stateIn` initialValue is Loading.
            // Do NOT start collecting — the stateIn initialValue is what we assert.
            val viewModel = fixture.build()

            // Then
            assertIs<CurrentlyListeningUiState.Loading>(viewModel.currentlyListeningState.value)
        }

    @Test
    fun `currentlyListeningState becomes Ready when authenticated and flow emits`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.activeSessionsFlow.value = listOf(createActiveSession(sessionId = "s-1"))

            // When
            val viewModel = fixture.build().also { keepStateHot(it.currentlyListeningState) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<CurrentlyListeningUiState.Ready>(viewModel.currentlyListeningState.value)
            assertEquals(1, ready.sessions.size)
            assertEquals("s-1", ready.sessions.first().sessionId)
        }

    @Test
    fun `currentlyListeningState becomes Ready empty when unauthenticated`() =
        runTest {
            // Given - unauthenticated auth state steers flatMapLatest to flowOf(emptyList())
            val fixture = createFixture(authState = AuthState.NeedsLogin())

            // When
            val viewModel = fixture.build().also { keepStateHot(it.currentlyListeningState) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<CurrentlyListeningUiState.Ready>(viewModel.currentlyListeningState.value)
            assertTrue(ready.isEmpty)
        }

    @Test
    fun `currentlyListeningState becomes Error when upstream throws`() =
        runTest {
            // Given - observeActiveSessions throws on collection
            val fixture = createFixture()
            every { fixture.activeSessionRepository.observeActiveSessions(any()) } returns
                flow { throw RuntimeException("boom") }

            // When
            val viewModel = fixture.build().also { keepStateHot(it.currentlyListeningState) }
            advanceUntilIdle()

            // Then
            val err = assertIs<CurrentlyListeningUiState.Error>(viewModel.currentlyListeningState.value)
            assertEquals("Failed to load currently listening", err.message)
        }

    // ========== Recently Added Tests ==========

    @Test
    fun `recentlyAddedState becomes Ready when flow emits`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.recentlyAddedFlow.value = listOf(createDiscoveryBook(id = "new-1", title = "New"))

            // When
            val viewModel = fixture.build().also { keepStateHot(it.recentlyAddedState) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<RecentlyAddedUiState.Ready>(viewModel.recentlyAddedState.value)
            assertEquals(1, ready.books.size)
            assertEquals("new-1", ready.books.first().id)
        }

    @Test
    fun `recentlyAddedState becomes Error when upstream throws`() =
        runTest {
            // Given
            val fixture = createFixture()
            every { fixture.bookRepository.observeRecentlyAddedBooks(any()) } returns
                flow { throw RuntimeException("boom") }

            // When
            val viewModel = fixture.build().also { keepStateHot(it.recentlyAddedState) }
            advanceUntilIdle()

            // Then
            val err = assertIs<RecentlyAddedUiState.Error>(viewModel.recentlyAddedState.value)
            assertEquals("Failed to load recently added", err.message)
        }

    // ========== Discover Shelves Tests ==========

    @Test
    fun `discoverShelvesState becomes Ready grouped by owner`() =
        runTest {
            // Given - two shelves from the same owner, one from another owner
            val fixture = createFixture()
            fixture.discoverShelvesFlow.value =
                listOf(
                    createShelf(id = "s1", ownerId = "alice", ownerDisplayName = "Alice"),
                    createShelf(id = "s2", ownerId = "alice", ownerDisplayName = "Alice"),
                    createShelf(id = "s3", ownerId = "bob", ownerDisplayName = "Bob"),
                )

            // When
            val viewModel = fixture.build().also { keepStateHot(it.discoverShelvesState) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<DiscoverShelvesUiState.Ready>(viewModel.discoverShelvesState.value)
            assertEquals(2, ready.users.size)
            assertEquals(3, ready.totalShelfCount)
            val alice = ready.users.single { it.user.id == "alice" }
            assertEquals(2, alice.shelves.size)
        }

    // ========== Discover Books Tests ==========

    @Test
    fun `discoverBooksState becomes Ready with random books on initial load`() =
        runTest {
            // Given
            val fixture = createFixture(randomBooks = listOf(createDiscoveryBook(id = "r-1")))

            // When
            val viewModel = fixture.build().also { keepStateHot(it.discoverBooksState) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<DiscoverBooksUiState.Ready>(viewModel.discoverBooksState.value)
            assertEquals(1, ready.books.size)
            assertEquals("r-1", ready.books.first().id)
        }

    @Test
    fun `discoverBooksState reloads when refresh is called`() =
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

    // ========== Initial Fetch Gate Tests ==========

    @Test
    fun `fetchInitialShelvesIfNeeded fetches when count is zero`() =
        runTest {
            // Given - Room is empty
            val fixture = createFixture(existingDiscoverShelfCount = 0)

            // When - init runs
            fixture.build()
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.shelfRepository.fetchAndCacheDiscoverShelves() }
        }

    @Test
    fun `fetchInitialShelvesIfNeeded skips when count is positive`() =
        runTest {
            // Given - Room already has discover shelves
            val fixture = createFixture(existingDiscoverShelfCount = 5)

            // When
            fixture.build()
            advanceUntilIdle()

            // Then
            verifySuspend(dev.mokkery.verify.VerifyMode.not) {
                fixture.shelfRepository.fetchAndCacheDiscoverShelves()
            }
        }
}
