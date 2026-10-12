@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.LeaderboardRepository
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Sealed UI state for the Discover leaderboard.
 *
 * [Loading] is the `stateIn` initial value — emitted before the first Room query
 * completes. [Data] carries the [LeaderboardSnapshot]; what is selected lives apart, in
 * [LeaderboardViewModel.selection], so the controls render in every state. [Empty] is reached when
 * nobody has listening time in the selected period. [Error] is emitted when the pipeline fails;
 * [LeaderboardViewModel.refresh] re-runs it.
 *
 * Changing the active category is a pure selection change — the snapshot already
 * contains all three lists, so no upstream DB re-query is triggered.
 */
sealed interface LeaderboardUiState {
    /** Pre-first-emission placeholder — the `stateIn` initial value. */
    data object Loading : LeaderboardUiState

    /** Nobody has listening time in the selected period (or nobody is on the roster yet). */
    data object Empty : LeaderboardUiState

    /**
     * Leaderboard data loaded and ready to render.
     *
     * @property snapshot All three category lists from the most recent Room emission. Which one is
     *   shown, and for which period, is [LeaderboardViewModel.selection].
     */
    data class Data(
        val snapshot: LeaderboardSnapshot,
    ) : LeaderboardUiState

    /**
     * The run failed; [error] says how. A refresh or a period pick starts another.
     *
     * @property error The typed failure.
     */
    data class Error(
        val error: AppError,
    ) : LeaderboardUiState
}

/**
 * ViewModel for the Discover screen leaderboard section.
 *
 * Architecture highlights:
 * - [selection] is its own state, independent of [uiState], so the period and category controls
 *   never depend on whether the board loaded.
 * - Period changes swap the upstream Room subscription via `flatMapLatest`.
 * - Category changes update [selection] only — no DB query, and [uiState] is untouched.
 * - The signed-in reader's id reaches the repository, which marks their row.
 * - Failures emit [LeaderboardUiState.Error] with a typed [AppError] (via `fallbackTo`, which
 *   re-throws cancellation) so the section never silently hides errors.
 * - ⛔ That fallback sits INSIDE a `flatMapLatest` over [pipelineRuns], so a failure ends one run
 *   rather than the flow the screen is subscribed to. Outside it, the Error was the flow's last
 *   word and a retry had nothing to restart.
 *
 * @property repo Repository that vends [LeaderboardSnapshot] flows from Room.
 */
class LeaderboardViewModel(
    private val repo: LeaderboardRepository,
    authSession: AuthSession,
) : ViewModel() {
    /** The period and ranking on screen; independent of [uiState], so the controls never disappear. */
    val selection: StateFlow<LeaderboardSelection>
        field = MutableStateFlow(LeaderboardSelection(LeaderboardPeriod.Week, LeaderboardCategory.Time))

    private val currentUserId =
        authSession.authState.map { (it as? AuthState.Authenticated)?.userId?.value }.distinctUntilChanged()

    private val pipelineRuns = MutableStateFlow(0)

    val uiState: StateFlow<LeaderboardUiState> =
        pipelineRuns
            .flatMapLatest { leaderboardPipeline() }
            .stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = LeaderboardUiState.Loading,
            )

    private fun leaderboardPipeline(): Flow<LeaderboardUiState> =
        combine(selection.map { it.period }.distinctUntilChanged(), currentUserId) { period, me -> period to me }
            .flatMapLatest { (period, me) -> repo.observeSnapshot(period, limit = LEADERBOARD_LIMIT, currentUserId = me) }
            .map<_, LeaderboardUiState> { snapshot ->
                // Nobody listened in the window: the board has nothing to rank, even with people on it.
                if (snapshot.time.none { it.totalSeconds > 0 }) LeaderboardUiState.Empty else LeaderboardUiState.Data(snapshot)
            }.fallbackTo { e ->
                logger.error(e) { "Error observing the leaderboard" }
                LeaderboardUiState.Error(ErrorMapper.map(e))
            }

    /** Select a period. A failed board restarts too, since its period control stays on screen. */
    fun selectPeriod(p: LeaderboardPeriod) {
        selection.update { it.copy(period = p) }
        restartIfFailed()
    }

    /** Select a ranking. The snapshot already holds all three, so nothing is re-queried. */
    fun selectCategory(c: LeaderboardCategory) {
        selection.update { it.copy(category = c) }
    }

    /** Re-run a failed leaderboard. A healthy one is already live on Room and is left alone. */
    fun refresh() {
        restartIfFailed()
    }

    private fun restartIfFailed() {
        if (uiState.value is LeaderboardUiState.Error) pipelineRuns.update { it + 1 }
    }
}

private const val LEADERBOARD_LIMIT = 20

private val logger = KotlinLogging.logger {}
