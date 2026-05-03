package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.turbineScope
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.BookEvent
import com.calypsan.listenup.client.domain.model.BookReadersResult
import com.calypsan.listenup.client.domain.model.ReaderInfo
import com.calypsan.listenup.client.domain.model.SessionSummary
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.SessionRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for BookReadersViewModel.
 *
 * Tests cover:
 * - Initial `Loading` state before `observeReaders` is called
 * - `Ready` emissions from the repository flow with mapped fields
 * - Empty yourSessions + otherReaders produce Ready with isEmpty = true
 * - Repository flow throws → Error state with the thrown message
 * - `observeReaders(sameBookId)` is a no-op when already active
 * - `refresh(bookId)` delegates to `SessionRepository.refreshBookReaders`
 * - Rapid book-switch emits one coherent sequence without cross-contamination
 * - SSE event for current book triggers debounced refresh
 * - SSE event for non-current book does not trigger refresh
 *
 * Uses Mokkery for mocking `SessionRepository`, `EventStreamRepository`, and `UserRepository`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookReadersViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val sessionRepository: SessionRepository = mock()
        val eventStreamRepository: EventStreamRepository = mock()
        val userRepository: UserRepository = mock()
        val readersFlow = MutableStateFlow(emptyResult())
        val bookEvents = MutableSharedFlow<BookEvent>()

        fun build(): BookReadersViewModel =
            BookReadersViewModel(
                sessionRepository = sessionRepository,
                eventStreamRepository = eventStreamRepository,
                userRepository = userRepository,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        every { fixture.sessionRepository.observeBookReaders(any()) } returns fixture.readersFlow
        everySuspend { fixture.sessionRepository.refreshBookReaders(any()) } returns Unit
        every { fixture.eventStreamRepository.bookEvents } returns fixture.bookEvents
        everySuspend { fixture.userRepository.getCurrentUser() } returns createUser()

        return fixture
    }

    // ========== Test Data Factories ==========

    companion object {
        private const val BOOK_ID = "book-1"

        private fun createUser(
            id: String = "user-1",
            displayName: String = "Reader",
        ): User =
            User(
                id = UserId(id),
                email = "reader@example.com",
                displayName = displayName,
                isAdmin = false,
                avatarType = "auto",
                avatarValue = null,
                avatarColor = "#6B7280",
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )

        private fun createSession(
            id: String = "session-1",
            startedAt: String = "2026-04-10T12:00:00Z",
            finishedAt: String? = null,
            isCompleted: Boolean = false,
        ): SessionSummary =
            SessionSummary(
                id = id,
                startedAt = startedAt,
                finishedAt = finishedAt,
                isCompleted = isCompleted,
                listenTimeMs = 0L,
            )

        private fun createReader(
            userId: String = "user-2",
            displayName: String = "Other Reader",
            isCurrentlyReading: Boolean = true,
            lastActivityAt: String = "2026-04-10T12:00:00Z",
        ): ReaderInfo =
            ReaderInfo(
                userId = userId,
                displayName = displayName,
                avatarColor = "#6B7280",
                isCurrentlyReading = isCurrentlyReading,
                currentProgress = 0.0,
                startedAt = "2026-04-10T12:00:00Z",
                finishedAt = null,
                lastActivityAt = lastActivityAt,
                completionCount = 0,
                isCurrentUser = false,
            )

        private fun emptyResult(): BookReadersResult =
            BookReadersResult(
                yourSessions = emptyList(),
                otherReaders = emptyList(),
                totalReaders = 0,
                totalCompletions = 0,
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
    fun `initial state is Loading before observeReaders is called`() =
        runTest {
            // Given
            val fixture = createFixture()

            // When - viewModel created, observeReaders NOT called
            val viewModel = fixture.build()

            turbineScope {
                val states = viewModel.state.testIn(backgroundScope)
                val initial = states.awaitItem()

                // Then - state is Loading
                assertIs<BookReadersUiState.Loading>(initial)
                states.cancel()
            }
        }

    // ========== Reactive Observation ==========

    @Test
    fun `observeReaders emits Ready with mapped fields when repository flow emits`() =
        runTest {
            // Given
            val fixture = createFixture()
            val session = createSession(id = "session-1")
            val other = createReader(userId = "user-2", displayName = "Other Reader")
            fixture.readersFlow.value =
                BookReadersResult(
                    yourSessions = listOf(session),
                    otherReaders = listOf(other),
                    totalReaders = 2,
                    totalCompletions = 1,
                )
            val viewModel = fixture.build()

            turbineScope {
                val states = viewModel.state.testIn(backgroundScope)
                states.awaitItem() // initial Loading

                // When
                viewModel.observeReaders(BOOK_ID)
                advanceUntilIdle()

                // Then
                val ready = assertIs<BookReadersUiState.Ready>(states.expectMostRecentItem())
                assertEquals(listOf(session), ready.yourSessions)
                assertEquals(listOf(other), ready.otherReaders)
                assertEquals(2, ready.totalReaders)
                assertEquals(1, ready.totalCompletions)
                // Current user info built from sessions + profile
                val currentUserInfo = ready.currentUserReaderInfo
                assertNotNull(currentUserInfo)
                assertEquals("user-1", currentUserInfo.userId)
                assertTrue(ready.hasYourHistory)
                assertTrue(ready.hasOtherReaders)
                states.cancel()
            }
        }

    @Test
    fun `Ready has isEmpty true when yourSessions and otherReaders are empty`() =
        runTest {
            // Given - default fixture emits an empty result
            val fixture = createFixture()
            val viewModel = fixture.build()

            turbineScope {
                val states = viewModel.state.testIn(backgroundScope)
                states.awaitItem() // initial Loading

                // When
                viewModel.observeReaders(BOOK_ID)
                advanceUntilIdle()

                // Then
                val ready = assertIs<BookReadersUiState.Ready>(states.expectMostRecentItem())
                assertTrue(ready.isEmpty)
                assertEquals(emptyList(), ready.allReaders)
                states.cancel()
            }
        }

    // ========== Error Handling ==========

    @Test
    fun `Error state emitted with thrown message when repository flow throws`() =
        runTest {
            // Given - repository flow that throws
            val fixture = TestFixture()
            every { fixture.sessionRepository.observeBookReaders(any()) } returns
                flow {
                    throw RuntimeException("boom")
                }
            everySuspend { fixture.sessionRepository.refreshBookReaders(any()) } returns Unit
            every { fixture.eventStreamRepository.bookEvents } returns fixture.bookEvents
            everySuspend { fixture.userRepository.getCurrentUser() } returns createUser()

            val viewModel = fixture.build()

            turbineScope {
                val states = viewModel.state.testIn(backgroundScope)
                states.awaitItem() // initial Loading

                // When
                viewModel.observeReaders(BOOK_ID)
                advanceUntilIdle()

                // Then
                val err = assertIs<BookReadersUiState.Error>(states.expectMostRecentItem())
                assertEquals("boom", err.message)
                states.cancel()
            }
        }

    // ========== Observation Gating ==========

    @Test
    fun `observeReaders is a no-op when already active for the same bookId`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            turbineScope {
                val states = viewModel.state.testIn(backgroundScope)
                states.awaitItem() // initial Loading

                // When - call twice with the same book id
                viewModel.observeReaders(BOOK_ID)
                advanceUntilIdle()
                viewModel.observeReaders(BOOK_ID)
                advanceUntilIdle()

                // Then - repository observed only once (MutableStateFlow idempotency)
                verify(VerifyMode.exactly(1)) { fixture.sessionRepository.observeBookReaders(BOOK_ID) }
                states.expectMostRecentItem() // drain emitted Ready before cancel
                states.cancel()
            }
        }

    // ========== Refresh ==========

    @Test
    fun `refresh calls refreshBookReaders on sessionRepository`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()

            turbineScope {
                val states = viewModel.state.testIn(backgroundScope)
                states.awaitItem() // initial Loading

                // When
                viewModel.refresh(BOOK_ID)
                advanceUntilIdle()

                // Then
                verifySuspend { fixture.sessionRepository.refreshBookReaders(BOOK_ID) }
                states.cancel()
            }
        }

    // ========== Race Condition Tests ==========

    @Test
    fun `rapid book-switch emits one coherent sequence without cross-contamination`() =
        runTest {
            val fixture = createFixture()
            val resultX =
                BookReadersResult(
                    yourSessions = emptyList(),
                    otherReaders = listOf(createReader(userId = "x-reader")),
                    totalReaders = 1,
                    totalCompletions = 0,
                )
            val resultY =
                BookReadersResult(
                    yourSessions = emptyList(),
                    otherReaders = listOf(createReader(userId = "y-reader"), createReader(userId = "y-reader-2")),
                    totalReaders = 2,
                    totalCompletions = 0,
                )
            val flowX = MutableStateFlow(resultX)
            val flowY = MutableStateFlow(resultY)
            every { fixture.sessionRepository.observeBookReaders("book-X") } returns flowX
            every { fixture.sessionRepository.observeBookReaders("book-Y") } returns flowY

            val vm = fixture.build()

            turbineScope {
                val states = vm.state.testIn(backgroundScope)
                states.awaitItem() // initial Loading

                vm.observeReaders("book-X")
                advanceUntilIdle()
                val xState = states.expectMostRecentItem()
                assertTrue(xState is BookReadersUiState.Ready)
                assertEquals(1, xState.totalReaders)

                vm.observeReaders("book-Y")
                advanceUntilIdle()
                val yState = states.expectMostRecentItem()
                assertTrue(yState is BookReadersUiState.Ready)
                // Y-specific assertion: 2 readers, not 1 from X
                assertEquals(2, yState.totalReaders)

                states.cancel()
            }
        }

    // ========== SSE Tests ==========

    @Test
    fun `SSE event for current book triggers debounced refresh`() =
        runTest {
            val fixture = createFixture()
            val vm = fixture.build()

            turbineScope {
                val states = vm.state.testIn(backgroundScope)
                states.awaitItem() // initial Loading

                vm.observeReaders("book-X")
                advanceUntilIdle()
                states.expectMostRecentItem() // consume Ready

                // Emit an SSE event for book-X
                fixture.bookEvents.emit(
                    BookEvent.ReadingSessionUpdated(
                        sessionId = "session-1",
                        bookId = "book-X",
                        isCompleted = false,
                        listenTimeMs = 0L,
                        finishedAt = null,
                    ),
                )

                // Advance past the 2-second debounce
                advanceTimeBy(2_500)
                advanceUntilIdle()

                verifySuspend(mode = VerifyMode.atLeast(1)) {
                    fixture.sessionRepository.refreshBookReaders("book-X")
                }

                states.cancel()
            }
        }

    @Test
    fun `SSE event for non-current book does not trigger refresh`() =
        runTest {
            val fixture = createFixture()
            val vm = fixture.build()

            turbineScope {
                val states = vm.state.testIn(backgroundScope)
                states.awaitItem() // initial Loading

                vm.observeReaders("book-X")
                advanceUntilIdle()
                states.expectMostRecentItem() // consume Ready

                // Emit an SSE event for a DIFFERENT book (book-Y) while VM is observing book-X
                fixture.bookEvents.emit(
                    BookEvent.ReadingSessionUpdated(
                        sessionId = "session-2",
                        bookId = "book-Y",
                        isCompleted = false,
                        listenTimeMs = 0L,
                        finishedAt = null,
                    ),
                )

                advanceTimeBy(3_000)
                advanceUntilIdle()

                verifySuspend(mode = VerifyMode.not) {
                    fixture.sessionRepository.refreshBookReaders("book-Y")
                }

                states.cancel()
            }
        }
}
