package com.calypsan.listenup.client.presentation.discover

import app.cash.turbine.test
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
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
 * 3. Selecting category Books without a new repo emit → category changes, snapshot unchanged.
 * 4. Selecting period Month → repo re-subscribes → [LeaderboardUiState.Data] with new snapshot.
 * 5. Empty snapshot (all three lists empty) → [LeaderboardUiState.Empty].
 * 6. Repo throws → [LeaderboardUiState.Error] with isRetryable = true.
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
            private val flowForPeriod: (LeaderboardPeriod) -> Flow<LeaderboardSnapshot>,
        ) : LeaderboardRepository {
            override fun observeSnapshot(
                period: LeaderboardPeriod,
                limit: Int,
            ): Flow<LeaderboardSnapshot> = flowForPeriod(period)
        }

        // ── 1. Initial state ──────────────────────────────────────────────────

        test("initial state is Loading before any collector") {
            runTest {
                val repo = FakeLeaderboardRepository { MutableSharedFlow() }
                val vm = LeaderboardViewModel(repo)
                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Loading>()
            }
        }

        // ── 2. First emission → Data ──────────────────────────────────────────

        test("first repo emission transitions to Data with Week and Time defaults") {
            runTest {
                val snapFlow = MutableSharedFlow<LeaderboardSnapshot>(replay = 1)
                val repo = FakeLeaderboardRepository { snapFlow }
                val vm = LeaderboardViewModel(repo)

                // Keep the StateFlow hot without blocking runTest completion.
                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                snapFlow.emit(snapshot("alice"))
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                data.period shouldBe LeaderboardPeriod.Week
                data.category shouldBe LeaderboardCategory.Time
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
                    FakeLeaderboardRepository {
                        subscribeCount++
                        snapFlow
                    }
                val vm = LeaderboardViewModel(repo)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                snapFlow.emit(snapshot(booksUserId = "bob"))
                advanceUntilIdle()

                val before = subscribeCount

                vm.selectCategory(LeaderboardCategory.Books)
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                data.category shouldBe LeaderboardCategory.Books
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
                    FakeLeaderboardRepository { period ->
                        when (period) {
                            LeaderboardPeriod.Week -> weekFlow
                            LeaderboardPeriod.Month -> monthFlow
                            else -> MutableSharedFlow()
                        }
                    }
                val vm = LeaderboardViewModel(repo)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                weekFlow.emit(snapshot("week-user"))
                advanceUntilIdle()

                vm.selectPeriod(LeaderboardPeriod.Month)
                monthFlow.emit(snapshot("month-user"))
                advanceUntilIdle()

                val data = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Data>()
                data.period shouldBe LeaderboardPeriod.Month
                data.snapshot.time
                    .single()
                    .userId shouldBe "month-user"
            }
        }

        // ── 5. Empty snapshot → Empty ─────────────────────────────────────────

        test("empty snapshot transitions to Empty state") {
            runTest {
                val snapFlow = MutableSharedFlow<LeaderboardSnapshot>(replay = 1)
                val repo = FakeLeaderboardRepository { snapFlow }
                val vm = LeaderboardViewModel(repo)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                snapFlow.emit(emptySnapshot)
                advanceUntilIdle()

                vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Empty>()
            }
        }

        // ── 6. Repo throws → Error ────────────────────────────────────────────

        test("repo flow throwing emits Error with isRetryable = true") {
            runTest {
                val repo = FakeLeaderboardRepository { flow { throw RuntimeException("db error") } }
                val vm = LeaderboardViewModel(repo)

                backgroundScope.launch(testDispatcher) { vm.uiState.collect {} }
                advanceUntilIdle()

                val error = vm.uiState.value.shouldBeInstanceOf<LeaderboardUiState.Error>()
                error.isRetryable shouldBe true
            }
        }

        // ── 7. Single emission carries all three lists ────────────────────────

        test("single snapshot emission contains all three pre-computed category lists") {
            runTest {
                val snapFlow = MutableSharedFlow<LeaderboardSnapshot>(replay = 1)
                val repo = FakeLeaderboardRepository { snapFlow }
                val vm = LeaderboardViewModel(repo)

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
    })
