package com.calypsan.listenup.client.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.usecase.activity.FetchActivitiesUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
 * - On first launch, fetches initial activities from API and stores in Room
 * - UI observes Room Flow and automatically updates
 * - Activity sync will be restored when its domain migrates to the renovated sync engine.
 * - After initial fetch, works completely offline
 *
 * @property activityRepository Repository for activity feed operations
 * @property fetchActivitiesUseCase Use case for fetching activities from API
 */
class ActivityFeedViewModel(
    private val activityRepository: ActivityRepository,
    private val fetchActivitiesUseCase: FetchActivitiesUseCase,
) : ViewModel() {
    init {
        // Fetch initial activities if Room is empty
        fetchInitialActivitiesIfNeeded()
    }

    /**
     * Observe recent activities from Room.
     * Automatically updates when SSE events add new activities.
     */
    val state: StateFlow<ActivityFeedUiState> =
        activityRepository
            .observeRecent(limit = MAX_ACTIVITIES)
            .map<_, ActivityFeedUiState> { activities ->
                ActivityFeedUiState.Ready(activities = activities.map { it.toUiModel() })
            }.onStart { emit(ActivityFeedUiState.Loading) }
            .catch { e ->
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                logger.error(e) { "Error observing activity feed" }
                emit(ActivityFeedUiState.Error("Failed to load activity feed"))
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = ActivityFeedUiState.Loading,
            )

    /**
     * Fetch initial activities from API if Room is empty.
     * This ensures data is available on first launch before any SSE events arrive.
     */
    private fun fetchInitialActivitiesIfNeeded() {
        viewModelScope.launch {
            val existingCount = activityRepository.count()
            if (existingCount > 0) {
                logger.debug { "Room has $existingCount activities, skipping initial fetch" }
                return@launch
            }

            logger.debug { "Room is empty, fetching initial activities from API" }
            fetchActivitiesUseCase(limit = INITIAL_FETCH_SIZE)
            // Not fatal if fails - Room Flow will show empty state, SSE will populate over time
        }
    }

    /**
     * Refresh activities from API.
     * Fetches latest activities and stores in Room.
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
        createdAt = createdAtMs,
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
    val createdAt: Long,
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
