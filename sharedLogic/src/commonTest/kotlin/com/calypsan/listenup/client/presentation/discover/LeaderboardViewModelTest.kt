package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.client.domain.repository.CommunityStats
import com.calypsan.listenup.client.domain.repository.LeaderboardCategory
import com.calypsan.listenup.client.domain.repository.LeaderboardEntry
import com.calypsan.listenup.client.domain.repository.LeaderboardPeriod
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.matcher.matches
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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

/**
 * Tests for [LeaderboardViewModel].
 *
 * Covers:
 * - Initial `Loading` before subscription (asserted on the `stateIn`
 *   `initialValue`, before any collector starts).
 * - Transition to `Ready` with defaults (TIME / WEEK) once flows emit.
 * - `selectCategory` re-selects the pre-sorted slice without upstream reload.
 * - `selectCategory(same)` is a no-op.
 * - `selectPeriod` swaps upstream observations via `flatMapLatest`.
 * - `selectPeriod(same)` is a no-op.
 * - Per-upstream `.catch` absorbs an upstream failure into an empty list so
 *   the pipeline degrades to `Ready` with empty entries rather than `Error`.
 * - Cache-fetch init gate: fetches when empty, skips when populated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixture ==========

    private class TestFixture {
        val leaderboardRepository: LeaderboardRepository = mock()

        // One flow per (period, category) combination so we can push targeted
        // updates in tests that care about period transitions.
        val timeEntriesFlow = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
        val booksEntriesFlow = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
        val streakEntriesFlow = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
        val communityStatsFlow = MutableStateFlow(DEFAULT_STATS)

        fun build(): LeaderboardViewModel = LeaderboardViewModel(leaderboardRepository)
    }

    private fun createFixture(isCacheEmpty: Boolean = false): TestFixture {
        val fixture = TestFixture()

        every {
            fixture.leaderboardRepository.observeLeaderboard(
                any(),
                matches { it == LeaderboardCategory.TIME },
                any(),
            )
        } returns fixture.timeEntriesFlow
        every {
            fixture.leaderboardRepository.observeLeaderboard(
                any(),
                matches { it == LeaderboardCategory.BOOKS },
                any(),
            )
        } returns fixture.booksEntriesFlow
        every {
            fixture.leaderboardRepository.observeLeaderboard(
                any(),
                matches { it == LeaderboardCategory.STREAK },
                any(),
            )
        } returns fixture.streakEntriesFlow
        every { fixture.leaderboardRepository.observeCommunityStats(any()) } returns fixture.communityStatsFlow

        everySuspend { fixture.leaderboardRepository.isUserStatsCacheEmpty() } returns isCacheEmpty
        everySuspend { fixture.leaderboardRepository.fetchAndCacheUserStats() } returns true

        return fixture
    }

    private fun TestScope.keepStateHot(viewModel: LeaderboardViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    // ========== Test Data Factories ==========

    companion object {
        private val DEFAULT_STATS = CommunityStats(totalTimeMs = 0L, totalBooks = 0, activeUsers = 0)

        private fun entry(
            userId: String,
            timeMs: Long = 0L,
            booksCount: Int = 0,
            streakDays: Int = 0,
            rank: Int = 0,
            isCurrentUser: Boolean = false,
        ): LeaderboardEntry =
            LeaderboardEntry(
                rank = rank,
                userId = userId,
                displayName = userId,
                avatarColor = "#FF0000",
                avatarType = "initials",
                avatarValue = null,
                timeMs = timeMs,
                booksCount = booksCount,
                streakDays = streakDays,
                isCurrentUser = isCurrentUser,
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
    fun `initial state is Loading before pipeline subscribes`() =
        runTest {
            // Given
            val fixture = createFixture()

            // When — do NOT start collecting; stateIn initialValue is what we assert.
            val viewModel = fixture.build()

            // Then
            assertIs<LeaderboardUiState.Loading>(viewModel.state.value)
        }

    // ========== Reactive Observation ==========

    @Test
    fun `Ready emitted with TIME WEEK defaults when flows emit`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.timeEntriesFlow.value =
                listOf(entry(userId = "u-time", timeMs = 100L, rank = 1))
            fixture.booksEntriesFlow.value =
                listOf(entry(userId = "u-books", booksCount = 5, rank = 1))
            fixture.streakEntriesFlow.value =
                listOf(entry(userId = "u-streak", streakDays = 3, rank = 1))

            // When
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<LeaderboardUiState.Ready>(viewModel.state.value)
            assertEquals(LeaderboardPeriod.WEEK, ready.selectedPeriod)
            assertEquals(LeaderboardCategory.TIME, ready.selectedCategory)
            assertEquals("u-time", ready.entries.single().userId)
            assertTrue(ready.hasData)
        }

    // ========== Category Selection ==========

    @Test
    fun `selectCategory switches the visible slice without upstream reload`() =
        runTest {
            // Given — entries pre-populated per category.
            val fixture = createFixture()
            fixture.timeEntriesFlow.value = listOf(entry(userId = "u-time", timeMs = 100L))
            fixture.booksEntriesFlow.value = listOf(entry(userId = "u-books", booksCount = 5))
            fixture.streakEntriesFlow.value = listOf(entry(userId = "u-streak", streakDays = 3))

            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // When
            viewModel.selectCategory(LeaderboardCategory.BOOKS)
            advanceUntilIdle()

            // Then
            val ready = assertIs<LeaderboardUiState.Ready>(viewModel.state.value)
            assertEquals(LeaderboardCategory.BOOKS, ready.selectedCategory)
            assertEquals("u-books", ready.entries.single().userId)

            // And — no NEW observeLeaderboard call per category (subscribed once at startup).
            // Mokkery defaults to exactly(1); that holds precisely because category change does not reload.
            verifySuspend {
                fixture.leaderboardRepository.observeLeaderboard(
                    any(),
                    matches { it == LeaderboardCategory.BOOKS },
                    any(),
                )
            }
        }

    @Test
    fun `selectCategory with same category is a no-op`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<LeaderboardUiState.Ready>(viewModel.state.value)
            assertEquals(LeaderboardCategory.TIME, ready.selectedCategory)

            // When — selecting the current category.
            viewModel.selectCategory(LeaderboardCategory.TIME)
            advanceUntilIdle()

            // Then — state is unchanged and each upstream only ever subscribed once.
            val after = assertIs<LeaderboardUiState.Ready>(viewModel.state.value)
            assertEquals(LeaderboardCategory.TIME, after.selectedCategory)
            verifySuspend {
                fixture.leaderboardRepository.observeLeaderboard(
                    any(),
                    matches { it == LeaderboardCategory.TIME },
                    any(),
                )
            }
        }

    // ========== Period Selection ==========

    @Test
    fun `selectPeriod triggers new upstream observations`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // When
            viewModel.selectPeriod(LeaderboardPeriod.MONTH)
            advanceUntilIdle()

            // Then — each per-category observation invoked again with MONTH,
            // and community stats re-subscribed with MONTH.
            verifySuspend {
                fixture.leaderboardRepository.observeLeaderboard(
                    matches { it == LeaderboardPeriod.MONTH },
                    matches { it == LeaderboardCategory.TIME },
                    any(),
                )
            }
            verifySuspend {
                fixture.leaderboardRepository.observeLeaderboard(
                    matches { it == LeaderboardPeriod.MONTH },
                    matches { it == LeaderboardCategory.BOOKS },
                    any(),
                )
            }
            verifySuspend {
                fixture.leaderboardRepository.observeLeaderboard(
                    matches { it == LeaderboardPeriod.MONTH },
                    matches { it == LeaderboardCategory.STREAK },
                    any(),
                )
            }
            verifySuspend {
                fixture.leaderboardRepository.observeCommunityStats(
                    matches { it == LeaderboardPeriod.MONTH },
                )
            }

            val ready = assertIs<LeaderboardUiState.Ready>(viewModel.state.value)
            assertEquals(LeaderboardPeriod.MONTH, ready.selectedPeriod)
        }

    @Test
    fun `selectPeriod with same period is a no-op`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // When — selecting the current (default) period.
            viewModel.selectPeriod(LeaderboardPeriod.WEEK)
            advanceUntilIdle()

            // Then — no MONTH-level observation ever happened.
            verifySuspend(VerifyMode.not) {
                fixture.leaderboardRepository.observeLeaderboard(
                    matches { it == LeaderboardPeriod.MONTH },
                    any(),
                    any(),
                )
            }
        }

    // ========== Per-upstream Catch ==========

    @Test
    fun `per-upstream catch absorbs failure into empty entries without tripping Error`() =
        runTest {
            // Given — the TIME upstream throws immediately.
            val fixture = TestFixture()
            every {
                fixture.leaderboardRepository.observeLeaderboard(
                    any(),
                    matches { it == LeaderboardCategory.TIME },
                    any(),
                )
            } returns flow { throw RuntimeException("boom") }
            every {
                fixture.leaderboardRepository.observeLeaderboard(
                    any(),
                    matches { it == LeaderboardCategory.BOOKS },
                    any(),
                )
            } returns fixture.booksEntriesFlow
            every {
                fixture.leaderboardRepository.observeLeaderboard(
                    any(),
                    matches { it == LeaderboardCategory.STREAK },
                    any(),
                )
            } returns fixture.streakEntriesFlow
            every { fixture.leaderboardRepository.observeCommunityStats(any()) } returns fixture.communityStatsFlow
            everySuspend { fixture.leaderboardRepository.isUserStatsCacheEmpty() } returns false
            everySuspend { fixture.leaderboardRepository.fetchAndCacheUserStats() } returns true

            fixture.booksEntriesFlow.value = listOf(entry(userId = "u-books", booksCount = 5))
            fixture.streakEntriesFlow.value = listOf(entry(userId = "u-streak", streakDays = 7))

            // When
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then — Ready with empty TIME entries; BOOKS and STREAK are still populated,
            // proving each upstream has its own independent catch.
            val ready = assertIs<LeaderboardUiState.Ready>(viewModel.state.value)
            assertEquals(0, ready.entriesByCategory.getValue(LeaderboardCategory.TIME).size)
            assertEquals(1, ready.entriesByCategory.getValue(LeaderboardCategory.BOOKS).size)
            assertEquals(1, ready.entriesByCategory.getValue(LeaderboardCategory.STREAK).size)
        }

    // ========== Initial Cache Fetch Gate ==========

    @Test
    fun `cache fetch runs when user stats cache is empty`() =
        runTest {
            // Given
            val fixture = createFixture(isCacheEmpty = true)

            // When — init runs.
            fixture.build()
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.leaderboardRepository.fetchAndCacheUserStats() }
        }

    @Test
    fun `cache fetch skipped when user stats cache is populated`() =
        runTest {
            // Given
            val fixture = createFixture(isCacheEmpty = false)

            // When
            fixture.build()
            advanceUntilIdle()

            // Then
            verifySuspend(VerifyMode.not) { fixture.leaderboardRepository.fetchAndCacheUserStats() }
        }
}
