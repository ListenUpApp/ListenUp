package com.calypsan.listenup.client.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.data.sync.ActivityRefreshSignal
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.usecase.activity.FetchActivitiesUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** Number of activities to fetch on initial load */
private const val INITIAL_FETCH_SIZE = 50

/** Maximum activities to observe from Room (avoids loading entire history) */
private const val MAX_ACTIVITIES = 100

/**
 * ViewModel for the Activity Feed section on the Discover screen.
 *
 * Offline-first architecture:
 * - On first launch, fetches initial activities from the feed RPC and stores in Room
 * - UI observes Room Flow and automatically updates
 * - On each [ActivityRefreshSignal] ping (server nudge or firehose reconnect), re-fetches the
 *   feed head into Room — the Room observation then repaints the UI
 * - After initial fetch, works completely offline
 *
 * @property activityRepository Repository for activity feed operations
 * @property fetchActivitiesUseCase Use case for fetching activities from the feed RPC
 * @property refreshSignal Pings when the feed may have changed (nudge or reconnect)
 */
class ActivityFeedViewModel internal constructor(
    private val activityRepository: ActivityRepository,
    private val fetchActivitiesUseCase: FetchActivitiesUseCase,
    private val refreshSignal: ActivityRefreshSignal,
) : ViewModel() {
    init {
        // Fetch initial activities if Room is empty
        fetchInitialActivitiesIfNeeded()
        // Re-fetch the feed head whenever the server signals a change; Room observation repaints.
        refreshSignal.signal
            .onEach { fetchActivitiesUseCase(limit = INITIAL_FETCH_SIZE) }
            .launchIn(viewModelScope)
    }

    /**
     * Observe recent activities from Room — the single read source.
     * Room repaints whenever the feed RPC refreshes the cache (ActivityChanged nudge or reconnect).
     */
    val state: StateFlow<ActivityFeedUiState> =
        activityRepository
            .observeRecent(limit = MAX_ACTIVITIES)
            .map<_, ActivityFeedUiState> { activities ->
                ActivityFeedUiState.Ready(activities = activities.map { it.toUiModel() })
            }.onStart { emit(ActivityFeedUiState.Loading) }
            .fallbackTo { e ->
                logger.error(e) { "Error observing activity feed" }
                ActivityFeedUiState.Error("Failed to load activity feed")
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = ActivityFeedUiState.Loading,
            )

    /**
     * Fetch the feed head from the RPC into Room if the cache is empty.
     * This ensures data is available on first launch before any ActivityChanged nudge arrives.
     */
    private fun fetchInitialActivitiesIfNeeded() {
        viewModelScope.launch {
            val existingCount = activityRepository.count()
            if (existingCount > 0) {
                logger.debug { "Room has $existingCount activities, skipping initial fetch" }
                return@launch
            }

            logger.debug { "Room is empty, fetching the feed head from the RPC" }
            fetchActivitiesUseCase(limit = INITIAL_FETCH_SIZE)
            // Not fatal if it fails — Room shows the empty state and the next nudge/reconnect refills.
        }
    }

    /**
     * Refresh the feed from the RPC.
     * Re-fetches the feed head into the Room cache; the Room observation then repaints.
     */
    fun refresh() {
        viewModelScope.launch {
            fetchActivitiesUseCase(limit = INITIAL_FETCH_SIZE)
        }
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
        userAvatarColor = user.avatarColor,
        userAvatarType = user.avatarType,
        userAvatarValue = user.avatarValue,
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
    val userAvatarColor: String,
    val userAvatarType: String,
    val userAvatarValue: String?,
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
        /** Whether there is data to display. */
        val hasData: Boolean
            get() = activities.isNotEmpty()

        /** Whether the feed is empty. */
        val isEmpty: Boolean
            get() = activities.isEmpty()
    }

    /** Upstream failure — section renders the message in error styling. */
    data class Error(
        val message: String,
    ) : ActivityFeedUiState
}
