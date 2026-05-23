package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.WeeklyStats
import com.calypsan.listenup.client.domain.repository.StatsRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for HomeStatsViewModel.
 *
 * Tests cover:
 * - Initial state and reactive observation
 * - Stats updates from repository flow
 * - Error handling when flow throws
 * - Formatted listen time (computed property)
 * - Derived state properties (hasData, hasGenreData, hasStreak)
 *
 * Uses Mokkery for mocking StatsRepository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeStatsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixtures ==========

    private class TestFixture {
        val statsRepository: StatsRepository = mock()
        val statsFlow = MutableStateFlow(WeeklyStats.empty())

        fun build(): HomeStatsViewModel =
            HomeStatsViewModel(
                statsRepository = statsRepository,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Default stub for reactive observation
        every { fixture.statsRepository.observeWeeklyStats() } returns fixture.statsFlow

        return fixture
    }

    private fun TestScope.keepStateHot(viewModel: HomeStatsViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    // ========== Test Data Factories ==========

    companion object {
        private fun createStats(
            totalSecondsThisWeek: Long = 0,
            currentStreakDays: Int = 0,
            longestStreakDays: Int = 0,
            dailyBuckets: List<DayBucket> = emptyList(),
            topGenres: List<GenreShare> = emptyList(),
        ): WeeklyStats =
            WeeklyStats(
                totalSecondsThisWeek = totalSecondsThisWeek,
                currentStreakDays = currentStreakDays,
                longestStreakDays = longestStreakDays,
                dailyBuckets = dailyBuckets,
                topGenres = topGenres,
            )

        private fun createDayBucket(
            dayOffsetFromToday: Int = 0,
            totalSeconds: Long = 3_600L,
        ): DayBucket = DayBucket(dayOffsetFromToday = dayOffsetFromToday, totalSeconds = totalSeconds)

        private fun createGenreShare(
            genreName: String = "Fiction",
            totalSeconds: Long = 3_600L,
        ): GenreShare = GenreShare(genreName = genreName, totalSeconds = totalSeconds)
    }

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
    fun `initial state is loading`() =
        runTest {
            // Given
            val fixture = createFixture()

            // When - viewModel created; `stateIn` initialValue is Loading
            // Do NOT start collecting here — the stateIn initialValue is what we assert.
            val viewModel = fixture.build()

            // Then - initial value is Loading (asserted before pipeline runs)
            assertIs<HomeStatsUiState.Loading>(viewModel.state.value)
        }

    @Test
    fun `init starts observation and transitions to Ready when flow emits`() =
        runTest {
            // Given
            val fixture = createFixture()

            // When - viewModel created, pipeline subscribes on first collect
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then - state transitions to Ready after flow emits
            assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
        }

    // ========== Reactive Observation Tests ==========

    @Test
    fun `observeStats updates state when flow emits`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // When - flow emits new data
            val stats =
                createStats(
                    totalSecondsThisWeek = 7_200L,
                    currentStreakDays = 3,
                    longestStreakDays = 5,
                )
            fixture.statsFlow.value = stats
            advanceUntilIdle()

            // Then - state should update reactively
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals(7_200L, ready.totalSecondsThisWeek)
            assertEquals(3, ready.currentStreakDays)
            assertEquals(5, ready.longestStreakDays)
        }

    @Test
    fun `stats update reactively when new listening events occur`() =
        runTest {
            // Given - start with some stats
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalSecondsThisWeek = 3_600L)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()
            assertEquals(
                3_600L,
                assertIs<HomeStatsUiState.Ready>(viewModel.state.value).totalSecondsThisWeek,
            )

            // When - new listening event added (Flow emits updated stats)
            fixture.statsFlow.value = createStats(totalSecondsThisWeek = 7_200L)
            advanceUntilIdle()

            // Then - UI updates immediately without manual refresh
            assertEquals(
                7_200L,
                assertIs<HomeStatsUiState.Ready>(viewModel.state.value).totalSecondsThisWeek,
            )
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `error state is set when flow throws`() =
        runTest {
            // Given - repository flow that throws
            val fixture = TestFixture()
            every { fixture.statsRepository.observeWeeklyStats() } returns
                flow {
                    throw RuntimeException("Database error")
                }
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val err = assertIs<HomeStatsUiState.Error>(viewModel.state.value)
            assertEquals("Failed to load stats: Database error", err.message)
        }

    // ========== Refresh Tests ==========

    @Test
    fun `refresh is no-op since data is observed reactively`() =
        runTest {
            // Given - ViewModel with reactive observation
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalSecondsThisWeek = 1_800L)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()
            val before = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)

            // When - refresh is called
            viewModel.refresh()
            advanceUntilIdle()

            // Then - state is unchanged (refresh is a no-op)
            val after = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals(before, after)
        }

    // ========== Formatted Listen Time Tests (seconds-based) ==========

    @Test
    fun `formattedListenTime shows 0m for zero time`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalSecondsThisWeek = 0)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("0m", ready.formattedListenTime)
        }

    @Test
    fun `formattedListenTime shows minutes only for less than one hour`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalSecondsThisWeek = 45 * 60L) // 45 minutes
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("45m", ready.formattedListenTime)
        }

    @Test
    fun `formattedListenTime shows hours only for exact hours`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalSecondsThisWeek = 2 * 60 * 60L) // 2 hours
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("2h", ready.formattedListenTime)
        }

    @Test
    fun `formattedListenTime shows hours and minutes`() =
        runTest {
            val fixture = createFixture()
            val twoHoursThirtyMinutes = (2 * 60 + 30) * 60L
            fixture.statsFlow.value = createStats(totalSecondsThisWeek = twoHoursThirtyMinutes)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("2h 30m", ready.formattedListenTime)
        }

    @Test
    fun `formattedListenTime shows large hours correctly`() =
        runTest {
            val fixture = createFixture()
            val fifteenHoursFortyFive = (15 * 60 + 45) * 60L
            fixture.statsFlow.value = createStats(totalSecondsThisWeek = fifteenHoursFortyFive)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("15h 45m", ready.formattedListenTime)
        }

    // ========== hasData Tests ==========

    @Test
    fun `hasData is false when all stats are empty`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = WeeklyStats.empty()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.hasData)
        }

    @Test
    fun `hasData is true when totalSecondsThisWeek greater than zero`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalSecondsThisWeek = 1L)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasData)
        }

    @Test
    fun `hasData is true when currentStreakDays greater than zero`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(currentStreakDays = 1)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasData)
        }

    @Test
    fun `hasData is true when longestStreakDays greater than zero`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(longestStreakDays = 5)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasData)
        }

    // ========== hasGenreData Tests ==========

    @Test
    fun `hasGenreData is false when topGenres is empty`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(topGenres = emptyList())
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.hasGenreData)
        }

    @Test
    fun `hasGenreData is true when topGenres is not empty`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(topGenres = listOf(createGenreShare()))
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasGenreData)
        }

    // ========== hasStreak Tests ==========

    @Test
    fun `hasStreak is false when both streaks are zero`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(currentStreakDays = 0, longestStreakDays = 0)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.hasStreak)
        }

    @Test
    fun `hasStreak is true when currentStreakDays greater than zero`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(currentStreakDays = 1, longestStreakDays = 0)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasStreak)
        }

    @Test
    fun `hasStreak is true when longestStreakDays greater than zero`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(currentStreakDays = 0, longestStreakDays = 7)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasStreak)
        }

    // ========== maxDailySeconds Tests ==========

    @Test
    fun `maxDailySeconds is zero when dailyBuckets is empty`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(dailyBuckets = emptyList())
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals(0L, ready.maxDailySeconds)
        }

    @Test
    fun `maxDailySeconds returns maximum from dailyBuckets`() =
        runTest {
            val fixture = createFixture()
            fixture.statsFlow.value =
                createStats(
                    dailyBuckets =
                        listOf(
                            createDayBucket(dayOffsetFromToday = 0, totalSeconds = 1_800L),
                            createDayBucket(dayOffsetFromToday = 1, totalSeconds = 3_600L),
                            createDayBucket(dayOffsetFromToday = 2, totalSeconds = 2_400L),
                        ),
                )
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals(3_600L, ready.maxDailySeconds)
        }
}
