package com.calypsan.listenup.client.presentation.home

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.WeeklyStats
import com.calypsan.listenup.client.domain.repository.StatsRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

/**
 * Tests for [HomeStatsViewModel].
 *
 * Covers:
 * - Initial state is [HomeStatsUiState.Loading]
 * - [WeeklyStats.isEverEmpty] == true → [HomeStatsUiState.Empty]
 * - Non-empty stats → [HomeStatsUiState.Data] with correct field mapping
 * - Reactive updates when the flow re-emits
 * - Display helpers on [HomeStatsUiState.Data] (formattedListenTime, hasStreak, etc.)
 * - Upstream error → [HomeStatsUiState.Error] with isRetryable = true
 *
 * Uses Turbine for Flow assertions and fake StatsRepository implementations
 * (no Mokkery) for hermetic seam-level testing.
 */
class HomeStatsViewModelTest :
    FunSpec({
        // ========== Initial state ==========

        test("initial state is Loading before any emission") {
            runTest {
                val vm = HomeStatsViewModel(stubRepo(MutableSharedFlow()))
                // stateIn initialValue is Loading; no emission has been made yet
                vm.uiState.value shouldBe HomeStatsUiState.Loading
            }
        }

        // ========== Empty state ==========

        test("emitting WeeklyStats.empty() transitions to Empty") {
            runTest {
                val vm = HomeStatsViewModel(stubRepo(MutableStateFlow(WeeklyStats.empty())))
                vm.uiState.test {
                    awaitUntil { it is HomeStatsUiState.Empty }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("stats with zero seconds and zero longest streak are Empty") {
            runTest {
                val stats =
                    WeeklyStats(
                        dailyBuckets = List(7) { DayBucket(it, 0L) },
                        currentStreakDays = 0,
                        longestStreakDays = 0,
                        topGenres = emptyList(),
                        totalSecondsThisWeek = 0L,
                    )
                val vm = HomeStatsViewModel(stubRepo(MutableStateFlow(stats)))
                vm.uiState.test {
                    awaitUntil { it is HomeStatsUiState.Empty }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Data state ==========

        test("non-empty stats transition to Data") {
            runTest {
                val stats = nonEmptyStats(totalSeconds = 3_600L, currentStreak = 2, longestStreak = 5)
                val vm = HomeStatsViewModel(stubRepo(MutableStateFlow(stats)))
                vm.uiState.test {
                    val data = awaitUntil { it is HomeStatsUiState.Data }.shouldBeInstanceOf<HomeStatsUiState.Data>()
                    data.totalSecondsThisWeek shouldBe 3_600L
                    data.currentStreakDays shouldBe 2
                    data.longestStreakDays shouldBe 5
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("Data carries the correct daily buckets and genres") {
            runTest {
                val buckets = listOf(DayBucket(0, 1_800L), DayBucket(1, 900L))
                val genres = listOf(GenreShare("Fiction", 1_200L))
                val stats =
                    WeeklyStats(
                        dailyBuckets = buckets,
                        currentStreakDays = 1,
                        longestStreakDays = 1,
                        topGenres = genres,
                        totalSecondsThisWeek = 2_700L,
                    )
                val vm = HomeStatsViewModel(stubRepo(MutableStateFlow(stats)))
                vm.uiState.test {
                    val data = awaitUntil { it is HomeStatsUiState.Data }.shouldBeInstanceOf<HomeStatsUiState.Data>()
                    data.dailyBuckets shouldBe buckets
                    data.topGenres shouldBe genres
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Reactive updates ==========

        test("state updates reactively when flow re-emits") {
            runTest {
                val flow = MutableStateFlow(WeeklyStats.empty())
                val vm = HomeStatsViewModel(stubRepo(flow))
                vm.uiState.test {
                    awaitUntil { it is HomeStatsUiState.Empty }

                    flow.value = nonEmptyStats(totalSeconds = 7_200L)
                    val data = awaitUntil { it is HomeStatsUiState.Data }.shouldBeInstanceOf<HomeStatsUiState.Data>()
                    data.totalSecondsThisWeek shouldBe 7_200L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("empty → data → empty round trip") {
            runTest {
                val flow = MutableStateFlow(WeeklyStats.empty())
                val vm = HomeStatsViewModel(stubRepo(flow))
                vm.uiState.test {
                    awaitUntil { it is HomeStatsUiState.Empty }

                    flow.value = nonEmptyStats(totalSeconds = 600L, longestStreak = 1)
                    awaitUntil { it is HomeStatsUiState.Data }

                    // Reset back to all-zero (isEverEmpty = true)
                    flow.value = WeeklyStats.empty()
                    awaitUntil { it is HomeStatsUiState.Empty }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Error state ==========

        test("upstream exception transitions to Error with isRetryable = true") {
            runTest {
                val repo =
                    object : StatsRepository {
                        override fun observeWeeklyStats(): Flow<WeeklyStats> =
                            flow {
                                throw RuntimeException("boom")
                            }
                    }
                val vm = HomeStatsViewModel(repo)
                vm.uiState.test {
                    val error = awaitUntil { it is HomeStatsUiState.Error }.shouldBeInstanceOf<HomeStatsUiState.Error>()
                    error.isRetryable shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        // ========== Display helpers on Data ==========

        test("formattedListenTime: 0 seconds → 0m") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = 0L,
                        currentStreakDays = 1,
                        longestStreakDays = 1,
                        dailyBuckets = emptyList(),
                        topGenres = emptyList(),
                    )
                data.formattedListenTime shouldBe "0m"
            }
        }

        test("formattedListenTime: 45 minutes") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = 45 * 60L,
                        currentStreakDays = 0,
                        longestStreakDays = 1,
                        dailyBuckets = emptyList(),
                        topGenres = emptyList(),
                    )
                data.formattedListenTime shouldBe "45m"
            }
        }

        test("formattedListenTime: exact 2 hours") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = 2 * 60 * 60L,
                        currentStreakDays = 0,
                        longestStreakDays = 1,
                        dailyBuckets = emptyList(),
                        topGenres = emptyList(),
                    )
                data.formattedListenTime shouldBe "2h"
            }
        }

        test("formattedListenTime: 2h 30m") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = (2 * 60 + 30) * 60L,
                        currentStreakDays = 0,
                        longestStreakDays = 1,
                        dailyBuckets = emptyList(),
                        topGenres = emptyList(),
                    )
                data.formattedListenTime shouldBe "2h 30m"
            }
        }

        test("hasData: false when all stats are zero") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = 0L,
                        currentStreakDays = 0,
                        longestStreakDays = 0,
                        dailyBuckets = emptyList(),
                        topGenres = emptyList(),
                    )
                data.hasData shouldBe false
            }
        }

        test("hasData: true when totalSecondsThisWeek > 0") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = 1L,
                        currentStreakDays = 0,
                        longestStreakDays = 1,
                        dailyBuckets = emptyList(),
                        topGenres = emptyList(),
                    )
                data.hasData shouldBe true
            }
        }

        test("hasStreak: false when both streaks are zero") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = 100L,
                        currentStreakDays = 0,
                        longestStreakDays = 0,
                        dailyBuckets = emptyList(),
                        topGenres = emptyList(),
                    )
                data.hasStreak shouldBe false
            }
        }

        test("hasStreak: true when longestStreakDays > 0") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = 0L,
                        currentStreakDays = 0,
                        longestStreakDays = 7,
                        dailyBuckets = emptyList(),
                        topGenres = emptyList(),
                    )
                data.hasStreak shouldBe true
            }
        }

        test("maxDailySeconds: returns max from dailyBuckets") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = 7_800L,
                        currentStreakDays = 1,
                        longestStreakDays = 1,
                        dailyBuckets =
                            listOf(
                                DayBucket(0, 1_800L),
                                DayBucket(1, 3_600L),
                                DayBucket(2, 2_400L),
                            ),
                        topGenres = emptyList(),
                    )
                data.maxDailySeconds shouldBe 3_600L
            }
        }

        test("maxDailySeconds: 0 when dailyBuckets is empty") {
            runTest {
                val data =
                    HomeStatsUiState.Data(
                        totalSecondsThisWeek = 0L,
                        currentStreakDays = 0,
                        longestStreakDays = 1,
                        dailyBuckets = emptyList(),
                        topGenres = emptyList(),
                    )
                data.maxDailySeconds shouldBe 0L
            }
        }
    })

// ========== Helpers ==========

private fun stubRepo(events: Flow<WeeklyStats>): StatsRepository =
    object : StatsRepository {
        override fun observeWeeklyStats(): Flow<WeeklyStats> = events
    }

/**
 * A [WeeklyStats] guaranteed to have [WeeklyStats.isEverEmpty] == false
 * (longestStreakDays = 1 ensures it). Callers override what they care about.
 */
private fun nonEmptyStats(
    totalSeconds: Long = 3_600L,
    currentStreak: Int = 1,
    longestStreak: Int = 1,
): WeeklyStats =
    WeeklyStats(
        dailyBuckets = listOf(DayBucket(0, totalSeconds)) + List(6) { DayBucket(it + 1, 0L) },
        currentStreakDays = currentStreak,
        longestStreakDays = longestStreak,
        topGenres = emptyList(),
        totalSecondsThisWeek = totalSeconds,
    )

/**
 * Drains items from this Turbine until [predicate] matches, then returns the matching item.
 *
 * Robust against the `stateIn(WhileSubscribed, initialValue)` collapse race where the initial
 * Loading state may or may not emit separately from the first upstream value under CI parallelism.
 * The emission count is non-deterministic (1 or 2 items depending on scheduler timing), so tests
 * must not assume a fixed count — instead drain until the desired state is seen.
 */
private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
    while (true) {
        val item = awaitItem()
        if (predicate(item)) return item
    }
}
