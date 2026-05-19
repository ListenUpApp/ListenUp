package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.client.domain.repository.DailyListening
import com.calypsan.listenup.client.domain.repository.GenreListening
import com.calypsan.listenup.client.domain.repository.HomeStats
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
        val statsFlow = MutableStateFlow(createEmptyStats())

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
        private fun createEmptyStats(): HomeStats =
            HomeStats(
                totalListenTimeMs = 0,
                currentStreakDays = 0,
                longestStreakDays = 0,
                dailyListening = emptyList(),
                genreBreakdown = emptyList(),
            )

        private fun createStats(
            totalListenTimeMs: Long = 0,
            currentStreakDays: Int = 0,
            longestStreakDays: Int = 0,
            dailyListening: List<DailyListening> = emptyList(),
            genreBreakdown: List<GenreListening> = emptyList(),
        ): HomeStats =
            HomeStats(
                totalListenTimeMs = totalListenTimeMs,
                currentStreakDays = currentStreakDays,
                longestStreakDays = longestStreakDays,
                dailyListening = dailyListening,
                genreBreakdown = genreBreakdown,
            )

        private fun createDailyListening(
            date: String = "2024-01-01",
            listenTimeMs: Long = 3_600_000L,
            booksListened: Int = 1,
        ): DailyListening =
            DailyListening(
                date = date,
                listenTimeMs = listenTimeMs,
                booksListened = booksListened,
            )

        private fun createGenreListening(
            genreSlug: String = "fiction",
            genreName: String = "Fiction",
            listenTimeMs: Long = 3_600_000L,
            percentage: Double = 50.0,
        ): GenreListening =
            GenreListening(
                genreSlug = genreSlug,
                genreName = genreName,
                listenTimeMs = listenTimeMs,
                percentage = percentage,
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
                    totalListenTimeMs = 7_200_000L,
                    currentStreakDays = 3,
                    longestStreakDays = 5,
                )
            fixture.statsFlow.value = stats
            advanceUntilIdle()

            // Then - state should update reactively
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals(7_200_000L, ready.totalListenTimeMs)
            assertEquals(3, ready.currentStreakDays)
            assertEquals(5, ready.longestStreakDays)
        }

    @Test
    fun `stats update reactively when new listening events occur`() =
        runTest {
            // Given - start with some stats
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalListenTimeMs = 3_600_000L)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()
            assertEquals(
                3_600_000L,
                assertIs<HomeStatsUiState.Ready>(viewModel.state.value).totalListenTimeMs,
            )

            // When - new listening event added (Flow emits updated stats)
            fixture.statsFlow.value = createStats(totalListenTimeMs = 7_200_000L)
            advanceUntilIdle()

            // Then - UI updates immediately without manual refresh
            assertEquals(
                7_200_000L,
                assertIs<HomeStatsUiState.Ready>(viewModel.state.value).totalListenTimeMs,
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
            fixture.statsFlow.value = createStats(totalListenTimeMs = 1_800_000L)
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

    // ========== Formatted Listen Time Tests ==========

    @Test
    fun `formattedListenTime shows 0m for zero time`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalListenTimeMs = 0)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("0m", ready.formattedListenTime)
        }

    @Test
    fun `formattedListenTime shows minutes only for less than one hour`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalListenTimeMs = 45 * 60 * 1000L) // 45 minutes
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("45m", ready.formattedListenTime)
        }

    @Test
    fun `formattedListenTime shows hours only for exact hours`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalListenTimeMs = 2 * 60 * 60 * 1000L) // 2 hours
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("2h", ready.formattedListenTime)
        }

    @Test
    fun `formattedListenTime shows hours and minutes`() =
        runTest {
            // Given
            val fixture = createFixture()
            val twoHoursThirtyMinutes = (2 * 60 + 30) * 60 * 1000L
            fixture.statsFlow.value = createStats(totalListenTimeMs = twoHoursThirtyMinutes)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("2h 30m", ready.formattedListenTime)
        }

    @Test
    fun `formattedListenTime shows large hours correctly`() =
        runTest {
            // Given
            val fixture = createFixture()
            val fifteenHoursFortyFive = (15 * 60 + 45) * 60 * 1000L
            fixture.statsFlow.value = createStats(totalListenTimeMs = fifteenHoursFortyFive)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals("15h 45m", ready.formattedListenTime)
        }

    // ========== hasData Tests ==========

    @Test
    fun `hasData is false when all stats are empty`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createEmptyStats()
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.hasData)
        }

    @Test
    fun `hasData is true when totalListenTimeMs greater than zero`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(totalListenTimeMs = 1L)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasData)
        }

    @Test
    fun `hasData is true when dailyListening is not empty`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value =
                createStats(
                    dailyListening = listOf(createDailyListening()),
                )
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasData)
        }

    @Test
    fun `hasData is true when currentStreakDays greater than zero`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(currentStreakDays = 1)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasData)
        }

    @Test
    fun `hasData is true when longestStreakDays greater than zero`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(longestStreakDays = 5)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasData)
        }

    // ========== hasGenreData Tests ==========

    @Test
    fun `hasGenreData is false when genreBreakdown is empty`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(genreBreakdown = emptyList())
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.hasGenreData)
        }

    @Test
    fun `hasGenreData is true when genreBreakdown is not empty`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value =
                createStats(
                    genreBreakdown = listOf(createGenreListening()),
                )
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasGenreData)
        }

    // ========== hasStreak Tests ==========

    @Test
    fun `hasStreak is false when both streaks are zero`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(currentStreakDays = 0, longestStreakDays = 0)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertFalse(ready.hasStreak)
        }

    @Test
    fun `hasStreak is true when currentStreakDays greater than zero`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(currentStreakDays = 1, longestStreakDays = 0)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasStreak)
        }

    @Test
    fun `hasStreak is true when longestStreakDays greater than zero`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(currentStreakDays = 0, longestStreakDays = 7)
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertTrue(ready.hasStreak)
        }

    // ========== maxDailyListenTimeMs Tests ==========

    @Test
    fun `maxDailyListenTimeMs is zero when dailyListening is empty`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value = createStats(dailyListening = emptyList())
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals(0L, ready.maxDailyListenTimeMs)
        }

    @Test
    fun `maxDailyListenTimeMs returns maximum from dailyListening`() =
        runTest {
            // Given
            val fixture = createFixture()
            fixture.statsFlow.value =
                createStats(
                    dailyListening =
                        listOf(
                            createDailyListening(date = "2024-01-01", listenTimeMs = 1_800_000L),
                            createDailyListening(date = "2024-01-02", listenTimeMs = 3_600_000L),
                            createDailyListening(date = "2024-01-03", listenTimeMs = 2_400_000L),
                        ),
                )
            val viewModel = fixture.build().also { keepStateHot(it) }
            advanceUntilIdle()

            // Then
            val ready = assertIs<HomeStatsUiState.Ready>(viewModel.state.value)
            assertEquals(3_600_000L, ready.maxDailyListenTimeMs)
        }
}
