package com.calypsan.listenup.client.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.repository.CommunityStats
import com.calypsan.listenup.client.domain.repository.LeaderboardCategory
import com.calypsan.listenup.client.domain.repository.LeaderboardEntry
import com.calypsan.listenup.client.domain.repository.LeaderboardPeriod
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** Maximum entries fetched from the repository per category. */
private const val LEADERBOARD_LIMIT = 10

/**
 * User intent for the leaderboard section.
 *
 * Held in a private [MutableStateFlow] so that period / category changes drive
 * the state pipeline without the pipeline ever having to read back from its own
 * output. Period changes trigger new upstream observations; category changes
 * simply select a different pre-sorted slice of the Map held on [Ready].
 */
private data class LeaderboardIntent(
    val selectedPeriod: LeaderboardPeriod = LeaderboardPeriod.WEEK,
    val selectedCategory: LeaderboardCategory = LeaderboardCategory.TIME,
)

/**
 * Raw per-category entries for a single period, keyed by category.
 *
 * The repository filters rows via `take(limit)` after sorting by the requested
 * category, so we cannot pin a single category and re-sort client-side without
 * missing users who would be top-10 for a different category. The pipeline
 * therefore observes all three categories per period and exposes the resulting
 * Map verbatim on [LeaderboardUiState.Ready] for the pager to read.
 */
private data class EntriesByCategory(
    val entries: Map<LeaderboardCategory, List<LeaderboardEntry>>,
)

/**
 * ViewModel for the Discover screen leaderboard section.
 *
 * Offline-first implementation that observes Room database flows:
 * - Period changes swap the upstream flows via `flatMapLatest`.
 * - Category changes update intent and select a different pre-sorted slice
 *   of [LeaderboardUiState.Ready.entriesByCategory] — no upstream reload.
 * - Automatic updates when listening events or activities change the Room data.
 *
 * Data sources:
 * - Week/Month/Year: Aggregated from local activities table.
 * - All-time: From cached user_stats (populated via API).
 * - Current user stats: Always from listening_events (authoritative).
 *
 * @property leaderboardRepository Repository for observing leaderboard data from Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeaderboardViewModel(
    private val leaderboardRepository: LeaderboardRepository,
) : ViewModel() {
    // ═══════════════════════════════════════════════════════════════════════
    // INTENT
    // ═══════════════════════════════════════════════════════════════════════

    private val intent = MutableStateFlow(LeaderboardIntent())

    // ═══════════════════════════════════════════════════════════════════════
    // PIPELINE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Per-category entries for the currently selected period.
     *
     * Swaps via `flatMapLatest` when the period changes. Each of the three
     * per-category flows has a defensive `.catch` so a transient failure in one
     * slice degrades to an empty list rather than tearing down the whole
     * section with an Error state.
     *
     * We observe all three categories concurrently because the repository
     * applies `sortedByDescending(valueFor(category)).take(limit)`, so top-N
     * for TIME is a different row set than for BOOKS or STREAK — we can't pin
     * one category and re-sort client-side. Cost: three Room subscriptions per
     * period (~12-15 DAO subscriptions under the hood); Room's invalidation
     * tracker handles this fine for a leaderboard that updates on user
     * activity, not per-keystroke.
     */
    private val entriesByCategoryFlow: Flow<EntriesByCategory> =
        intent
            .map { it.selectedPeriod }
            .distinctUntilChanged()
            .flatMapLatest { period ->
                combine(
                    observeCategoryOrEmpty(period, LeaderboardCategory.TIME),
                    observeCategoryOrEmpty(period, LeaderboardCategory.BOOKS),
                    observeCategoryOrEmpty(period, LeaderboardCategory.STREAK),
                ) { timeEntries, bookEntries, streakEntries ->
                    EntriesByCategory(
                        entries =
                            mapOf(
                                LeaderboardCategory.TIME to timeEntries,
                                LeaderboardCategory.BOOKS to bookEntries,
                                LeaderboardCategory.STREAK to streakEntries,
                            ),
                    )
                }
            }

    private fun observeCategoryOrEmpty(
        period: LeaderboardPeriod,
        category: LeaderboardCategory,
    ): Flow<List<LeaderboardEntry>> =
        leaderboardRepository
            .observeLeaderboard(period, category, LEADERBOARD_LIMIT)
            .catch { e ->
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                logger.error(e) { "observeLeaderboard($period, $category) failed; emitting empty" }
                emit(emptyList())
            }

    /**
     * Community aggregate stats for the currently selected period.
     *
     * Swaps via `flatMapLatest` on period changes. Transient failures degrade
     * to `null` (no stats row) rather than killing the section.
     */
    private val communityStatsFlow: Flow<CommunityStats?> =
        intent
            .map { it.selectedPeriod }
            .distinctUntilChanged()
            .flatMapLatest { period ->
                leaderboardRepository
                    .observeCommunityStats(period)
                    .map<CommunityStats, CommunityStats?> { it }
                    .catch { e ->
                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                        logger.error(e) { "observeCommunityStats($period) failed; emitting null" }
                        emit(null)
                    }
            }

    val state: StateFlow<LeaderboardUiState> =
        combine(
            intent,
            entriesByCategoryFlow,
            communityStatsFlow,
        ) { intentValue, byCategory, stats ->
            LeaderboardUiState.Ready(
                selectedPeriod = intentValue.selectedPeriod,
                selectedCategory = intentValue.selectedCategory,
                entriesByCategory = byCategory.entries,
                communityStats = stats,
            ) as LeaderboardUiState
        }.catch { e ->
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            logger.error(e) { "Leaderboard pipeline failed" }
            emit(LeaderboardUiState.Error("Failed to load leaderboard"))
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = LeaderboardUiState.Loading,
        )

    // ═══════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════

    init {
        // Fetch user stats on startup if the All-time cache is empty. Runs
        // independently of the state pipeline so there is no coupling between
        // the one-shot side effect and the declarative flow composition.
        viewModelScope.launch {
            if (leaderboardRepository.isUserStatsCacheEmpty()) {
                logger.info { "User stats cache empty, fetching from API" }
                leaderboardRepository.fetchAndCacheUserStats()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Select a new category. No upstream reload — just re-selects the
     * pre-sorted slice from [LeaderboardUiState.Ready.entriesByCategory].
     */
    fun selectCategory(category: LeaderboardCategory) {
        if (category == intent.value.selectedCategory) return
        logger.debug { "Category changed to $category" }
        intent.update { it.copy(selectedCategory = category) }
    }

    /**
     * Select a new period. Triggers the pipeline's `flatMapLatest` to swap
     * upstream observations for the new period.
     */
    fun selectPeriod(period: LeaderboardPeriod) {
        if (period == intent.value.selectedPeriod) return
        logger.info { "Period changed from ${intent.value.selectedPeriod} to $period" }
        intent.update { it.copy(selectedPeriod = period) }
    }

    /**
     * Refresh leaderboard data.
     *
     * Offline-first: Room flows auto-update, so this is a no-op. Kept for UI
     * compatibility with the pull-to-refresh gesture.
     */
    fun refresh() {
        logger.debug { "Refresh requested (no-op for offline-first)" }
    }
}

/**
 * UI state for the leaderboard section.
 *
 * Sealed hierarchy: `Loading` is the `stateIn` initial value; the first
 * emission from the combine pipeline flips to `Ready`; terminal pipeline
 * failures emit `Error`. Per-upstream failures degrade gracefully to empty
 * entries / null stats inside `Ready`.
 */
sealed interface LeaderboardUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : LeaderboardUiState

    /**
     * Leaderboard data loaded and ready to render.
     *
     * @property entriesByCategory Per-category pre-sorted entries. The
     *   repository's `take(limit)` happens after sorting, so top-10-by-time is
     *   not necessarily top-10-by-books; each category therefore has its own
     *   independent top-N list.
     */
    data class Ready(
        val selectedPeriod: LeaderboardPeriod,
        val selectedCategory: LeaderboardCategory,
        val entriesByCategory: Map<LeaderboardCategory, List<LeaderboardEntry>>,
        val communityStats: CommunityStats?,
    ) : LeaderboardUiState {
        init {
            require(LeaderboardCategory.entries.all { it in entriesByCategory }) {
                "entriesByCategory must contain all categories; missing: " +
                    LeaderboardCategory.entries.filterNot { it in entriesByCategory }
            }
        }

        /** Entries for the currently selected category. Non-null: Ready's invariant. */
        val entries: List<LeaderboardEntry>
            get() = entriesByCategory.getValue(selectedCategory)

        /** Whether any category has data to display. */
        val hasData: Boolean
            get() = entriesByCategory.values.any { it.isNotEmpty() }

        /** Whether there are community stats. */
        val hasCommunityStats: Boolean
            get() = communityStats != null
    }

    /** Terminal pipeline failure — the section renders the message in error styling. */
    data class Error(
        val message: String,
    ) : LeaderboardUiState
}
