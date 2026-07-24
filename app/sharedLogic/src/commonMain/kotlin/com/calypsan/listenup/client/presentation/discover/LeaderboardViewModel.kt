@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Sealed UI state for the Discover leaderboard.
 *
 * [Loading] is the `stateIn` initial value — emitted before the first Room query
 * completes. [Data] carries the [LeaderboardSnapshot] plus the user's current
 * [period] + [category] selection. [Empty] is reached when all three snapshot lists
 * are empty. [Error] is emitted on an unrecoverable pipeline failure.
 *
 * Changing the active [category] is a pure state filter — the snapshot already
 * contains all three lists, so no upstream DB re-query is triggered.
 */
sealed interface LeaderboardUiState {
    /** Pre-first-emission placeholder — the `stateIn` initial value. */
    data object Loading : LeaderboardUiState

    /** No entries in any category for the selected period. */
    data object Empty : LeaderboardUiState

    /**
     * Leaderboard data loaded and ready to render.
     *
     * @property snapshot All three category lists from the most recent Room emission.
     * @property period The currently selected time period.
     * @property category The currently active tab. Changing it costs zero DB queries —
     *   just pick the corresponding list from [snapshot].
     */
    data class Data(
        val snapshot: LeaderboardSnapshot,
        val period: LeaderboardPeriod,
        val category: LeaderboardCategory,
    ) : LeaderboardUiState

    /**
     * Terminal pipeline failure.
     *
     * @property isRetryable True when a retry may succeed (transient error).
     */
    data class Error(
        val isRetryable: Boolean,
    ) : LeaderboardUiState
}

/**
 * ViewModel for the Discover screen leaderboard section.
 *
 * Architecture highlights:
 * - Period changes swap the upstream Room subscription via `flatMapLatest`.
 * - Category changes update [category] only — no DB query.
 * - A single `combine` merges the period/snapshot stream with category, so every
 *   render is driven by the same downstream state machine.
 * - Failures emit [LeaderboardUiState.Error] (via `fallbackTo`, which re-throws
 *   cancellation) so the section never silently hides errors.
 *
 * @property repo Repository that vends [LeaderboardSnapshot] flows from Room.
 */
class LeaderboardViewModel(
    private val repo: LeaderboardRepository,
) : ViewModel() {
    private val period = MutableStateFlow<LeaderboardPeriod>(LeaderboardPeriod.Week)
    private val category = MutableStateFlow(LeaderboardCategory.Time)

    val uiState: StateFlow<LeaderboardUiState> =
        combine(
            period.flatMapLatest { p ->
                repo.observeSnapshot(p, limit = 20).map { snap -> p to snap }
            },
            category,
        ) { (p, snap), cat ->
            if (snap.time.isEmpty() && snap.books.isEmpty() && snap.streak.isEmpty()) {
                LeaderboardUiState.Empty
            } else {
                LeaderboardUiState.Data(snap, p, cat)
            }
        }.fallbackTo {
            LeaderboardUiState.Error(isRetryable = true)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LeaderboardUiState.Loading,
        )

    /**
     * Select a new leaderboard period. Triggers a new upstream Room subscription
     * via `flatMapLatest`, briefly emitting [LeaderboardUiState.Loading] until the
     * new query completes.
     */
    fun selectPeriod(p: LeaderboardPeriod) {
        period.value = p
    }

    /**
     * Select a new leaderboard category. Filters the existing snapshot — no
     * upstream Room re-query is triggered.
     */
    fun selectCategory(c: LeaderboardCategory) {
        category.value = c
    }
}
