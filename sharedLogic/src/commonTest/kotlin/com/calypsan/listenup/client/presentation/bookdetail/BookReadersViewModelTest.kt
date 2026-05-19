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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
class BookReadersViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        // ========== Test Data Factories ==========

        val testBookId = "book-1"

        fun createUser(
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

        fun createSession(
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

        fun createReader(
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

        fun emptyResult(): BookReadersResult =
            BookReadersResult(
                yourSessions = emptyList(),
                otherReaders = emptyList(),
                totalReaders = 0,
                totalCompletions = 0,
            )

        // ========== Test Fixtures ==========

        class TestFixture {
            val sessionRepository: SessionRepository = mock()
            val eventStreamRepository: EventStreamRepository = mock()
            val userRepository: UserRepository = mock()
            val readersFlow =
                MutableStateFlow(
                    BookReadersResult(
                        yourSessions = emptyList(),
                        otherReaders = emptyList(),
                        totalReaders = 0,
                        totalCompletions = 0,
                    ),
                )
            val bookEvents = MutableSharedFlow<BookEvent>()

            fun build(): BookReadersViewModel =
                BookReadersViewModel(
                    sessionRepository = sessionRepository,
                    eventStreamRepository = eventStreamRepository,
                    userRepository = userRepository,
                )
        }

        fun createFixture(createUser: () -> User): TestFixture {
            val fixture = TestFixture()

            every { fixture.sessionRepository.observeBookReaders(any()) } returns fixture.readersFlow
            everySuspend { fixture.sessionRepository.refreshBookReaders(any()) } returns Unit
            every { fixture.eventStreamRepository.bookEvents } returns fixture.bookEvents
            everySuspend { fixture.userRepository.getCurrentUser() } returns createUser()

            return fixture
        }

        beforeTest {
            Dispatchers.setMain(testDispatcher)
        }

        afterTest {
            Dispatchers.resetMain()
        }

        // ========== Initial State ==========

        test("initial state is Loading before observeReaders is called") {
            runTest {
                // Given
                val fixture = createFixture { createUser() }

                // When - viewModel created, observeReaders NOT called
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    val initial = states.awaitItem()

                    // Then - state is Loading
                    initial.shouldBeInstanceOf<BookReadersUiState.Loading>()
                    states.cancel()
                }
            }
        }

        // ========== Reactive Observation ==========

        test("observeReaders emits Ready with mapped fields when repository flow emits") {
            runTest {
                // Given
                val fixture = createFixture { createUser() }
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
                    viewModel.observeReaders(testBookId)
                    advanceUntilIdle()

                    // Then
                    val ready = states.expectMostRecentItem() as BookReadersUiState.Ready
                    ready.yourSessions shouldBe listOf(session)
                    ready.otherReaders shouldBe listOf(other)
                    ready.totalReaders shouldBe 2
                    ready.totalCompletions shouldBe 1
                    // Current user info built from sessions + profile
                    val currentUserInfo = ready.currentUserReaderInfo
                    currentUserInfo.shouldNotBeNull()
                    currentUserInfo.userId shouldBe "user-1"
                    (ready.hasYourHistory) shouldBe true
                    (ready.hasOtherReaders) shouldBe true
                    states.cancel()
                }
            }
        }

        test("Ready has isEmpty true when yourSessions and otherReaders are empty") {
            runTest {
                // Given - default fixture emits an empty result
                val fixture = createFixture { createUser() }
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.observeReaders(testBookId)
                    advanceUntilIdle()

                    // Then
                    val ready = states.expectMostRecentItem() as BookReadersUiState.Ready
                    (ready.isEmpty) shouldBe true
                    ready.allReaders shouldBe emptyList()
                    states.cancel()
                }
            }
        }

        // ========== Error Handling ==========

        test("Error state emitted with thrown message when repository flow throws") {
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
                    viewModel.observeReaders(testBookId)
                    advanceUntilIdle()

                    // Then
                    val err = states.expectMostRecentItem() as BookReadersUiState.Error
                    err.message shouldBe "boom"
                    states.cancel()
                }
            }
        }

        // ========== Observation Gating ==========

        test("observeReaders is a no-op when already active for the same bookId") {
            runTest {
                // Given
                val fixture = createFixture { createUser() }
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When - call twice with the same book id
                    viewModel.observeReaders(testBookId)
                    advanceUntilIdle()
                    viewModel.observeReaders(testBookId)
                    advanceUntilIdle()

                    // Then - repository observed only once (MutableStateFlow idempotency)
                    verify(VerifyMode.exactly(1)) { fixture.sessionRepository.observeBookReaders(testBookId) }
                    states.expectMostRecentItem() // drain emitted Ready before cancel
                    states.cancel()
                }
            }
        }

        // ========== Refresh ==========

        test("refresh calls refreshBookReaders on sessionRepository") {
            runTest {
                // Given
                val fixture = createFixture { createUser() }
                val viewModel = fixture.build()

                turbineScope {
                    val states = viewModel.state.testIn(backgroundScope)
                    states.awaitItem() // initial Loading

                    // When
                    viewModel.refresh(testBookId)
                    advanceUntilIdle()

                    // Then
                    verifySuspend { fixture.sessionRepository.refreshBookReaders(testBookId) }
                    states.cancel()
                }
            }
        }

        // ========== Race Condition Tests ==========

        test("rapid book-switch emits one coherent sequence without cross-contamination") {
            runTest {
                val fixture = createFixture { createUser() }
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
                    (xState is BookReadersUiState.Ready) shouldBe true
                    (xState as BookReadersUiState.Ready).totalReaders shouldBe 1

                    vm.observeReaders("book-Y")
                    advanceUntilIdle()
                    val yState = states.expectMostRecentItem()
                    (yState is BookReadersUiState.Ready) shouldBe true
                    // Y-specific assertion: 2 readers, not 1 from X
                    (yState as BookReadersUiState.Ready).totalReaders shouldBe 2

                    states.cancel()
                }
            }
        }

        // ========== SSE Tests ==========

        test("SSE event for current book triggers debounced refresh") {
            runTest {
                val fixture = createFixture { createUser() }
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
        }

        test("SSE event for non-current book does not trigger refresh") {
            runTest {
                val fixture = createFixture { createUser() }
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
    })
