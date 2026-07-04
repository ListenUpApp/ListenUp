package com.calypsan.listenup.client.presentation.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.core.fallbackTo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

private val logger = KotlinLogging.logger {}

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** Maximum activities to observe from Room (avoids loading entire history) */
private const val MAX_ACTIVITIES = 100

/**
 * ViewModel for the Activity Feed section on the Discover screen.
 *
 * Offline-first architecture: activities are a Room-mirrored sync domain, so the feed is kept
 * current by catch-up + the live SSE tail with no per-screen fetching. This ViewModel is a pure
 * Room observer — it reads [ActivityRepository.observeRecent] and repaints whenever the mirror
 * changes.
 *
 * @property activityRepository Repository for activity feed reads (the local mirror).
 */
class ActivityFeedViewModel internal constructor(
    private val activityRepository: ActivityRepository,
) : ViewModel() {
    /**
     * Observe recent activities from Room — the single read source.
     * Room repaints whenever the mirror advances (catch-up or live SSE tail).
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
