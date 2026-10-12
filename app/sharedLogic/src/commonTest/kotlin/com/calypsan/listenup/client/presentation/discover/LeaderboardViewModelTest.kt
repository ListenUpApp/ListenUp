package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import com.calypsan.listenup.client.test.SimulatedFailure
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for [LeaderboardViewModel].
 *
 * Uses a hand-written [FakeLeaderboardRepository] backed by [MutableSharedFlow]s so
 * we can push targeted emissions without Mokkery's overhead. All indefinitely-running
 * coroutines (keeping [LeaderboardViewModel.uiState] hot) are launched in
 * [backgroundScope] so they do not cause [UncompletedCoroutinesError].
 *
 * Covers:
 * 1. Initial state is [LeaderboardUiState.Loading] (stateIn initial value).
 * 2. First repo emission → [LeaderboardUiState.Data] with correct period / category.
 * 3. Selecting category Books → the selection changes; the board and its subscription do not.
 * 4. Selecting period Month → repo re-subscribes → [LeaderboardUiState.Data] with new snapshot.
 * 5. Empty snapshot (all three lists empty) → [LeaderboardUiState.Empty].
 * 6. Repo throws → [LeaderboardUiState.Error] carrying the typed AppError.
 * 7. Single emission carries all three category lists pre-computed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeEach { Dispatchers.setMain(testDispatcher) }
        afterEach { Dispatchers.resetMain() }

        // ── Helpers ───────────────────────────────────────────────────────────

        fun entry(
            userId: String,
            totalSeconds: Long = 100L,
        ) = LeaderboardEntry(
            rank = 1,
            userId = userId,
            displayName = userId,
            totalSeconds = totalSeconds,
            booksFinished = 0,
            currentStreakDays = 0,
            longestStreakDays = 0,
            isYou = false,
        )

        fun snapshot(
            timeUserId: String = "u-time",
            booksUserId: String = "u-books",
            streakUserId: String = "u-streak",
        ) = LeaderboardSnapshot(
            time = listOf(entry(timeUserId)),
            books = listOf(entry(booksUserId)),
            streak = listOf(entry(streakUserId)),
        )

        val emptySnapshot = LeaderboardSnapshot(emptyList(), emptyList(), emptyList())

        // Simple fake — swappable per-test
        class FakeLeaderboardRepository(
            private val flowFor: (LeaderboardPeriod, String?) -> Flow<LeaderboardSnapshot>,
        ) : LeaderboardRepository {
            override fun observeSnapshot(
                period: LeaderboardPeriod,
                limit: Int,
                currentUserId: String?,
            ): Flow<LeaderboardSnapshot> = flowFor(period, currentUserId)
        }

        fun signedInAs(userId: String?): AuthSession =
            mock {
                every { authState } returns
                    MutableStateFlow(
                        if (userId == null) {
                            AuthState.Initializing
                        } else {
                            AuthState.Authenticated(userId = UserId(userId), sessionId = SessionId("s"))
                        },
                    )
            }

        fun vm(
            repo: LeaderboardRepository,
            me: String? = "me",
        ) = LeaderboardViewModel(repo, signedInAs(me))

        // ── 1. Initial state ──────────────────────────────────────────────────

        test("initial state is Loading before any collector") {
            runTest {
                val repo = FakeLeaderboardRepository { _, _ -> MutableSharedFlow() }
                val vm = vm(repo)
                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Loading>()
            }
        }

        // ── 2. First emission → Data ──────────────────────────────────────────

        test("first repo emission transitions to Data with Week and Time defaults") {
            runTest {
                val snapFlow = MutableSharedFlow<LeaderboardSnapshot>(replay = 1)
                val repo = FakeLeaderboardRepository { _, _ -> snapFlow }
                val vm = vm(repo)

                // Keep the StateFlow hot without blocking runTest completion.
                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                snapFlow.emit(snapshot("alice"))
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                vm.selection.value shouldBe LeaderboardSelection(LeaderboardPeriod.Week, LeaderboardCategory.Time)
                data.snapshot.time
                    .single()
                    .userId shouldBe "alice"
            }
        }

        // ── 3. Category change — no re-fetch ─────────────────────────────────

        test("selectCategory updates category without triggering a new repo subscription") {
            runTest {
                val snapFlow = MutableSharedFlow<LeaderboardSnapshot>(replay = 1)
                var subscribeCount = 0
                val repo =
                    FakeLeaderboardRepository { _, _ ->
                        subscribeCount++
                        snapFlow
                    }
                val vm = vm(repo)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                snapFlow.emit(snapshot(booksUserId = "bob"))
                advanceUntilIdle()

                val before = subscribeCount
                val dataBefore = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()

                vm.selectCategory(LeaderboardCategory.Books)
                advanceUntilIdle()

                vm.selection.value.category shouldBe LeaderboardCategory.Books
                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                // A category pick leaves the board's data untouched: the very same instance.
                (data === dataBefore) shouldBe true
                data.snapshot.books
                    .single()
                    .userId shouldBe "bob"
                // No new subscription — same count
                subscribeCount shouldBe before
            }
        }

        // ── 4. Period change — re-subscribes ──────────────────────────────────

        test("selectPeriod re-subscribes with the new period and emits Data") {
            runTest {
                val weekFlow = MutableSharedFlow<LeaderboardSnapshot>(replay = 1)
                val monthFlow = MutableSharedFlow<LeaderboardSnapshot>(replay = 1)
                val repo =
                    FakeLeaderboardRepository { period, _ ->
                        when (period) {
                            LeaderboardPeriod.Week -> weekFlow
                            LeaderboardPeriod.Month -> monthFlow
                            LeaderboardPeriod.Year, LeaderboardPeriod.AllTime -> MutableSharedFlow()
                        }
                    }
                val vm = vm(repo)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                weekFlow.emit(snapshot("week-user"))
                advanceUntilIdle()

                vm.selectPeriod(LeaderboardPeriod.Month)
                monthFlow.emit(snapshot("month-user"))
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                vm.selection.value.period shouldBe LeaderboardPeriod.Month
                data.snapshot.time
                    .single()
                    .userId shouldBe "month-user"
            }
        }

        // ── 5. Empty snapshot → Empty ─────────────────────────────────────────

        test("empty snapshot transitions to Empty state") {
            runTest {
                val snapFlow = MutableSharedFlow<LeaderboardSnapshot>(replay = 1)
                val repo = FakeLeaderboardRepository { _, _ -> snapFlow }
                val vm = vm(repo)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                snapFlow.emit(emptySnapshot)
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Empty>()
            }
        }

        // ── 6. Repo throws → Error ────────────────────────────────────────────

        test("repo flow throwing emits Error carrying the typed AppError") {
            runTest {
                val repo = FakeLeaderboardRepository { _, _ -> flow { throw SimulatedFailure("boom") } }
                val vm = vm(repo)
                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()

                vm.uiState.value
                    .shouldBeInstanceOf<LeaderboardUiState.Error>()
                    .error
                    .shouldBeInstanceOf<UnexpectedClientError>()
            }
        }

        // ── 6b. Error recovers ────────────────────────────────────────────────

        test("Error recovers when refresh is called") {
            // ⛔ The regression: `isRetryable = true` promised a retry the ViewModel could not
            // deliver — the fallback sat outside any restartable run, so the Error ended the flow.
            runTest {
                var collections = 0
                val repo =
                    FakeLeaderboardRepository { _, _ ->
                        flow {
                            collections++
                            if (collections == 1) throw SimulatedFailure("transient")
                            emit(snapshot("alice"))
                        }
                    }
                val vm = vm(repo)
                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()
                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Error>()

                vm.refresh()
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                data.snapshot.time
                    .single()
                    .userId shouldBe "alice"
            }
        }

        test("Error recovers when a period is picked") {
            // The Android error state keeps the period chips on screen; picking one is a retry.
            runTest {
                var collections = 0
                val repo =
                    FakeLeaderboardRepository { _, _ ->
                        flow {
                            collections++
                            if (collections == 1) throw SimulatedFailure("transient")
                            emit(snapshot("alice"))
                        }
                    }
                val vm = vm(repo)
                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()
                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Error>()

                vm.selectPeriod(LeaderboardPeriod.Month)
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                vm.selection.value.period shouldBe LeaderboardPeriod.Month
            }
        }

        // ── 7. Single emission carries all three lists ────────────────────────

        test("single snapshot emission contains all three pre-computed category lists") {
            runTest {
                val snapFlow = MutableSharedFlow<LeaderboardSnapshot>(replay = 1)
                val repo = FakeLeaderboardRepository { _, _ -> snapFlow }
                val vm = vm(repo)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                snapFlow.emit(snapshot("t", "b", "s"))
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                data.snapshot.time
                    .single()
                    .userId shouldBe "t"
                data.snapshot.books
                    .single()
                    .userId shouldBe "b"
                data.snapshot.streak
                    .single()
                    .userId shouldBe "s"
            }
        }

        test("the selection exists while the board is Loading, Empty and Error") {
            runTest {
                val failing = FakeLeaderboardRepository { _, _ -> flow { throw SimulatedFailure("boom") } }
                val vm = vm(failing)
                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Loading>()
                vm.selectPeriod(LeaderboardPeriod.Year)
                vm.selection.value.period shouldBe LeaderboardPeriod.Year

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()
                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Error>()
                vm.selectCategory(LeaderboardCategory.Streak)
                vm.selection.value shouldBe LeaderboardSelection(LeaderboardPeriod.Year, LeaderboardCategory.Streak)
            }
        }

        test("a period with nobody listening is Empty, even though the roster has people") {
            runTest {
                val zero = LeaderboardSnapshot(time = listOf(entry("ada", totalSeconds = 0L)), books = emptyList(), streak = emptyList())
                val vm = vm(FakeLeaderboardRepository { _, _ -> flowOf(zero) })
                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Empty>()
            }
        }

        test("the signed-in reader's id reaches the repository, so their row can be marked") {
            runTest {
                var askedFor: String? = null
                val vm =
                    vm(
                        FakeLeaderboardRepository { _, me ->
                            askedFor = me
                            flowOf(snapshot())
                        },
                        me = "me",
                    )
                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()

                askedFor shouldBe "me"
            }
        }
    })
