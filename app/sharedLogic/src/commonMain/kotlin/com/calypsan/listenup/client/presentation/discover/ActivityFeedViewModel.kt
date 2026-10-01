package com.calypsan.listenup.client.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.core.fallbackTo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** Maximum activities to observe from Room (avoids loading entire history) */
private const val MAX_ACTIVITIES = 100

/**
 * ViewModel for the Activity Feed section on the Discover screen.
 *
 * Offline-first architecture: activities are a Room-mirrored sync domain, so the feed is kept
 * current by catch-up + the live firehose tail with no per-screen fetching. This ViewModel is a pure
 * Room observer — it reads [ActivityRepository.observeRecent] and repaints whenever the mirror
 * changes.
 *
 * @property activityRepository Repository for activity feed reads (the local mirror).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityFeedViewModel internal constructor(
    private val activityRepository: ActivityRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {
    /**
     * Bumped by [refresh] to re-run a failed observation.
     *
     * ⛔ The fallback sits INSIDE the [flatMapLatest], so a failure ends one run rather than the flow
     * the screen is subscribed to. Outside it, the Error was the flow's last word: a refresh re-synced
     * Room underneath a feed that could no longer hear it. `LibraryViewModel` has the same shape for
     * the same reason.
     */
    private val observationRuns = MutableStateFlow(0)

    /**
     * Observe recent activities from Room — the single read source.
     * Room repaints whenever the mirror advances (catch-up or live firehose tail).
     */
    val state: StateFlow<ActivityFeedUiState> =
        observationRuns
            .flatMapLatest { observeFeed() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = ActivityFeedUiState.Loading,
            )

    private fun observeFeed(): Flow<ActivityFeedUiState> =
        activityRepository
            .observeRecent(limit = MAX_ACTIVITIES)
            .map<_, ActivityFeedUiState> { activities ->
                // One sitting reads as one line: the server records an activity per closed playback
                // span, so an interrupted evening otherwise arrives as a wall of entries.
                // …and a sitting too short to be news (an accidental tap) is not announced at all.
                ActivityFeedUiState.Ready(
                    activities =
                        activities
                            .map {
                                it.toUiModel()
                            }.coalesceListeningSessions()
                            .withoutFleetingSittings(),
                )
            }.onStart { emit(ActivityFeedUiState.Loading) }
            .fallbackTo { e ->
                logger.error(e) { "Error observing activity feed" }
                ActivityFeedUiState.Error("Failed to load activity feed")
            }

    /**
     * Manual pull-to-refresh (Never-Stranded fallback). The feed is a Room mirror that stays current
     * on its own, but an explicit refresh forces a lifecycle reconcile so a missed live event
     * self-heals on demand. Fire-and-forget: Room repaints the feed when the reconcile lands rows.
     *
     * A feed that failed is also re-observed. A healthy one is already live on Room, and re-running
     * it would repaint the same rows.
     */
    fun refresh() {
        if (state.value is ActivityFeedUiState.Error) observationRuns.update { it + 1 }
        viewModelScope.launch { syncRepository.refresh() }
    }
}

/**
 * Convert Activity domain model to UI model.
 */
private fun Activity.toUiModel(): ActivityUiModel =
    ActivityUiModel(
        id = id,
        userId = userId,
        type = type,
        occurredAt = occurredAtMs,
        userDisplayName = user.displayName,
        bookId = book?.id,
        bookTitle = book?.title,
        bookAuthorName = book?.authorName,
        bookCoverPath = book?.coverPath,
        isReread = isReread,
        durationMs = durationMs,
        milestoneValue = milestoneValue,
        milestoneUnit = milestoneUnit,
        shelfId = shelfId,
        shelfName = shelfName,
    )

/**
 * UI model for a single activity in the feed.
 * Flattened representation of Activity domain model for UI consumption.
 */
data class ActivityUiModel(
    val id: String,
    val userId: String,
    val type: String,
    val occurredAt: Long,
    val userDisplayName: String,
    val bookId: String?,
    val bookTitle: String?,
    val bookAuthorName: String?,
    val bookCoverPath: String?,
    val isReread: Boolean,
    val durationMs: Long,
    val milestoneValue: Int,
    val milestoneUnit: String?,
    val shelfId: String?,
    val shelfName: String?,
)

/**
 * UI state for the activity feed section.
 *
 * Sealed hierarchy: `Loading` → first `observeRecent()` emission flips to
 * `Ready`; upstream failures emit `Error`. Data comes from Room so errors are
 * rare but still surfaced rather than silently swallowed.
 */
sealed interface ActivityFeedUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : ActivityFeedUiState

    /** Activities loaded from Room. */
    data class Ready(
        val activities: List<ActivityUiModel>,
    ) : ActivityFeedUiState {
        /** Whether the feed is empty. */
        val isEmpty: Boolean
            get() = activities.isEmpty()
    }

    /** Upstream failure — section renders the message in error styling. */
    data class Error(
        val message: String,
    ) : ActivityFeedUiState
}
