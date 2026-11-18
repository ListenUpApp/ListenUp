package com.calypsan.listenup.client.presentation.discover

import com.calypsan.listenup.api.result.onFailure
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.ActiveSession
import com.calypsan.listenup.client.domain.model.Activity
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.ActiveSessionRepository
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.fallbackTo
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi

private val logger = KotlinLogging.logger {}

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
 * Above those events sits whoever else is listening right now, one row each
 * ([ActiveSessionRepository.observeListeningNow]): an activity is written only when a listen closes,
 * so without them a person mid-book would show on Discover's Listening now and nowhere here. When
 * their listen closes the live row goes and the server's "listened to N minutes" row takes its place.
 *
 * @property activityRepository Repository for activity feed reads (the local mirror).
 * @param activeSessionRepository The cached presence roster, for who is listening right now.
 * @param authSession The signed-in reader, so each row knows whether it is theirs ([ActivityUiModel.isYou]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityFeedViewModel internal constructor(
    private val activityRepository: ActivityRepository,
    activeSessionRepository: ActiveSessionRepository,
    private val syncRepository: SyncRepository,
    authSession: AuthSession,
) : ViewModel() {
    private val currentUserId: Flow<String?> =
        authSession.authState
            .map { auth -> if (auth is AuthState.Authenticated) auth.userId.value else null }
            .distinctUntilChanged()

    /** Who other than the viewer is listening right now; nobody while signed out. */
    private val listeningNow: Flow<List<ActiveSession>> =
        currentUserId.flatMapLatest { me ->
            if (me == null) flowOf(emptyList()) else activeSessionRepository.observeListeningNow(me)
        }

    /**
     * Bumped by [refresh] to re-run a failed observation.
     *
     * ⛔ The fallback sits INSIDE the [flatMapLatest], so a failure ends one run rather than the flow
     * the screen is subscribed to. Outside it, the Error was the flow's last word: a refresh re-synced
     * Room underneath a feed that could no longer hear it. `LibraryViewModel` has the same shape for
     * the same reason.
     */
    private val observationRuns = MutableStateFlow(0)

    /** Whether a [refresh] is under way: a pull-to-refresh's spinner, held until the reconcile lands. */
    val refreshing: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /**
     * Observe recent activities from Room — the single read source.
     * Room repaints whenever the mirror advances (catch-up or live firehose tail).
     */
    val state: StateFlow<ActivityFeedUiState> =
        observationRuns
            .flatMapLatest { observeFeed() }
            .stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = ActivityFeedUiState.Loading,
            )

    private fun observeFeed(): Flow<ActivityFeedUiState> =
        combine(
            activityRepository.observeRecent(limit = MAX_ACTIVITIES),
            currentUserId,
            listeningNow,
        ) { activities, me, live ->
            // One sitting reads as one line: the server records an activity per closed playback
            // span, so an interrupted evening otherwise arrives as a wall of entries.
            // …and a sitting too short to be news (an accidental tap) is not announced at all.
            ActivityFeedUiState.Ready(
                activities =
                    activities
                        .map {
                            it.toUiModel(me)
                        }.coalesceListeningSessions()
                        .withoutFleetingSittings(),
                listeningNow = live.map { it.toListeningNowUiModel() },
            ) as ActivityFeedUiState
        }.onStart { emit(ActivityFeedUiState.Loading) }
            .fallbackTo { e ->
                logger.error(e) { "Error observing activity feed" }
                ActivityFeedUiState.Error(ErrorMapper.map(e))
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
        viewModelScope.launch { refreshNow() }
    }

    /** [refresh], but suspending until the lifecycle reconcile has finished, so a pull spinner can wait for it. */
    suspend fun refreshNow() {
        if (state.value is ActivityFeedUiState.Error) observationRuns.update { it + 1 }
        refreshing.value = true
        try {
            syncRepository.refresh().onFailure { logger.warn { "Activity refresh failed: ${it.message}" } }
        } finally {
            refreshing.value = false
        }
    }
}

/**
 * Convert Activity domain model to UI model.
 */
private fun Activity.toUiModel(currentUserId: String?): ActivityUiModel =
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
        bookCoverHash = book?.coverHash,
        isReread = isReread,
        durationMs = durationMs,
        milestoneValue = milestoneValue,
        milestoneUnit = milestoneUnit,
        shelfId = shelfId,
        shelfName = shelfName,
        isYou = currentUserId != null && userId == currentUserId,
    )

/** A live roster row as the feed's "is listening to" row. */
private fun ActiveSession.toListeningNowUiModel(): ListeningNowUiModel =
    ListeningNowUiModel(
        userId = userId,
        userDisplayName = user.displayName,
        bookId = bookId,
        bookTitle = book.title,
        bookAuthorName = book.authorName,
        bookCoverPath = book.coverPath,
        bookCoverHash = book.coverHash,
        startedAt = lastActiveAtMs,
    )

/**
 * One row of the Activity feed: something that happened ([ActivityUiModel]), or someone listening
 * right now ([ListeningNowUiModel]). Sealed so every client draws exactly these two kinds, and a live
 * row can never carry an event's type, time or duration.
 */
sealed interface ActivityFeedEntry {
    /** Stable identity across emissions, for list keys. */
    val id: String
}

/**
 * Someone other than the viewer listening right now — "Kyle Harnett is listening to Red Rising",
 * with "Listening now" where an event shows its time.
 *
 * At most one per person, so the person is its identity. It lasts exactly as long as the cached
 * presence roster says they are listening; then it goes, and the "listened to" activity written when
 * their listen closed takes its place. Never the viewer's own.
 *
 * @property userId The listener.
 * @property userDisplayName Their name as the roster gave it.
 * @property bookId The book they are listening to.
 * @property bookTitle Its title, the row's highlight.
 * @property bookAuthorName Its credited author(s), or null when it has none.
 * @property bookCoverPath The book's local cover file, or null when not downloaded on this device.
 * @property bookCoverHash The book's cover content version, or null when it has no cover.
 * @property startedAt Epoch-ms their listen began: live rows sort newest-started first.
 */
data class ListeningNowUiModel(
    val userId: String,
    val userDisplayName: String,
    val bookId: String,
    val bookTitle: String,
    val bookAuthorName: String?,
    val bookCoverPath: String?,
    val bookCoverHash: String?,
    val startedAt: Long,
) : ActivityFeedEntry {
    override val id: String = "listening-now:$userId"
}

/**
 * UI model for a single activity in the feed.
 * Flattened representation of Activity domain model for UI consumption.
 *
 * @property bookCoverPath The book's local cover file, or null when not downloaded on this device.
 * @property bookCoverHash The book's cover content version, or null when it has no cover.
 * @property isYou True when the signed-in reader is the actor; the row then reads "You".
 */
data class ActivityUiModel(
    override val id: String,
    val userId: String,
    val type: String,
    val occurredAt: Long,
    val userDisplayName: String,
    val bookId: String?,
    val bookTitle: String?,
    val bookAuthorName: String?,
    val bookCoverPath: String?,
    val bookCoverHash: String?,
    val isReread: Boolean,
    val durationMs: Long,
    val milestoneValue: Int,
    val milestoneUnit: String?,
    val shelfId: String?,
    val shelfName: String?,
    val isYou: Boolean,
) : ActivityFeedEntry

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

    /**
     * The feed: [listeningNow] (newest-started first) above [activities] (newest first). Clients
     * draw [entries], which holds both in that order.
     */
    data class Ready(
        val activities: List<ActivityUiModel>,
        val listeningNow: List<ListeningNowUiModel> = emptyList(),
    ) : ActivityFeedUiState {
        /** Every row in the order the feed shows them: a live row sorts as now, above every event. */
        val entries: List<ActivityFeedEntry> = listeningNow + activities

        /** Whether the feed is empty. */
        val isEmpty: Boolean
            get() = entries.isEmpty()
    }

    /** The section failed; [error] says how, and a refresh retries it. */
    data class Error(
        val error: AppError,
    ) : ActivityFeedUiState
}
