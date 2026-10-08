package com.calypsan.listenup.client.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.stableAvatarColorHex
import com.calypsan.listenup.client.data.local.db.PublicProfileDao
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.domain.model.ProfileShelfSummary
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

private val logger = KotlinLogging.logger {}

/**
 * UI state for the [UserProfileViewModel].
 *
 * Single [Ready] variant covers both own-profile and other-user paths — the screen
 * renders identically aside from the `isOwnProfile` admin-control toggle. Header and
 * stats are sourced uniformly from the synced `public_profiles` row; only the shelves
 * source differs (own → local synced mirror; other → one-shot RPC).
 *
 * The avatar image is not carried here — the screen resolves it reactively by `userId`
 * through the canonical `rememberUserAvatarImage`, so a synced avatar change or a completed
 * download repaints in real time without the ViewModel re-emitting.
 */
sealed interface UserProfileUiState {
    /** Pre-[UserProfileViewModel.loadProfile]. */
    data object Idle : UserProfileUiState

    /** Fetching or observing profile data. */
    data object Loading : UserProfileUiState

    /** Profile loaded. */
    data class Ready(
        val userId: String,
        val isOwnProfile: Boolean,
        val displayName: String,
        val avatarColor: String,
        val tagline: String?,
        val totalListenTimeMs: Long,
        val booksFinished: Int,
        val currentStreak: Int,
        val longestStreak: Int,
        val recentBooks: List<ProfileRecentBook>,
        val publicShelves: List<ProfileShelfSummary>,
    ) : UserProfileUiState

    /** Load failed (only used when no header data is available — e.g. an unsynced other-user row). */
    data class Error(
        val message: String,
    ) : UserProfileUiState
}

/**
 * ViewModel for the User Profile screen.
 *
 * Both own and other-user paths derive header + stats from the synced `public_profiles`
 * Room row ([PublicProfileDao.observeById]). Shelves split by path: own profiles read the
 * locally-mirrored synced shelves ([ShelfRepository.observeMyShelves]); other profiles
 * fetch the caller-accessible public shelves once over RPC ([ShelfRepository.getUserShelves]).
 * Both paths carry the same "Recently listened" strip, live from the synced activity feed
 * ([ActivityRepository.observeRecentlyListened]).
 *
 * The header never blocks on shelves: a shelf failure renders an empty shelf list, not an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileViewModel internal constructor(
    private val publicProfileDao: PublicProfileDao,
    private val shelfRepository: ShelfRepository,
    private val userRepository: UserRepository,
    private val activityRepository: ActivityRepository,
) : ViewModel() {
    private val requestFlow = MutableStateFlow<LoadRequest?>(null)

    val state: StateFlow<UserProfileUiState> =
        requestFlow
            .flatMapLatest { request ->
                if (request == null) {
                    flowOf(UserProfileUiState.Idle)
                } else {
                    profileFlow(request.userId)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = UserProfileUiState.Idle,
            )

    /**
     * Load the profile for [userId]. When [forceRefresh] is true, re-runs the pipeline
     * even if [userId] hasn't changed (used by pull-to-refresh).
     */
    fun loadProfile(
        userId: String,
        forceRefresh: Boolean = false,
    ) {
        val current = requestFlow.value
        if (current != null && current.userId == userId && !forceRefresh) return
        val nextCounter = (current?.refreshCounter ?: 0) + 1
        requestFlow.value = LoadRequest(userId, nextCounter)
    }

    /** Force a refresh of the current profile. */
    fun refresh() {
        val current = requestFlow.value ?: return
        requestFlow.value = current.copy(refreshCounter = current.refreshCounter + 1)
    }

    private fun profileFlow(userId: String): Flow<UserProfileUiState> =
        flow {
            emit(UserProfileUiState.Loading)
            val isOwn = userRepository.getCurrentUser()?.run { id.value } == userId
            if (isOwn) {
                emitAll(ownProfileFlow(userId))
            } else {
                emitAll(otherProfileFlow(userId))
            }
        }

    /**
     * Own profile: header + stats from the synced row, falling back to the local [User]
     * (header only, stats 0) while the row is briefly absent. Shelves come from the
     * locally-mirrored synced shelves and merge into [UserProfileUiState.Ready] reactively.
     */
    private fun ownProfileFlow(userId: String): Flow<UserProfileUiState> =
        combine(
            flow = publicProfileDao.observeById(userId),
            flow2 = userRepository.observeCurrentUser(),
            flow3 = shelfRepository.observeMyShelves(userId),
            flow4 = recentBooksFlow(userId),
        ) { row, currentUser, shelves, recentBooks ->
            when {
                row != null -> {
                    readyFromRow(
                        userId = userId,
                        isOwn = true,
                        row = row,
                        shelves = shelves.toSummaries(),
                        recentBooks = recentBooks,
                    )
                }

                currentUser != null -> {
                    readyFromUser(
                        userId = userId,
                        user = currentUser,
                        shelves = shelves.toSummaries(),
                        recentBooks = recentBooks,
                    )
                }

                else -> {
                    UserProfileUiState.Error("No user data available")
                }
            }
        }

    /**
     * Other profile: header + stats from the synced row; shelves fetched once over RPC.
     * A shelf failure yields an empty shelf list (header still renders); an absent row
     * yields an error, since no header data is available.
     */
    private fun otherProfileFlow(userId: String): Flow<UserProfileUiState> =
        flow {
            val shelves =
                when (val result = shelfRepository.getUserShelves(userId)) {
                    is AppResult.Success -> {
                        result.data.toSummaries()
                    }

                    is AppResult.Failure -> {
                        logger.warn { "Failed to load shelves for user $userId" }
                        emptyList()
                    }
                }
            emitAll(
                combine(publicProfileDao.observeById(userId), recentBooksFlow(userId)) { row, recentBooks ->
                    if (row == null) {
                        logger.error { "No public profile row for user: $userId" }
                        UserProfileUiState.Error("Failed to load profile")
                    } else {
                        readyFromRow(
                            userId = userId,
                            isOwn = false,
                            row = row,
                            shelves = shelves,
                            recentBooks = recentBooks,
                        )
                    }
                },
            )
        }

    /** The "Recently listened" strip: books [userId] listened to, live from the synced activity feed. */
    private fun recentBooksFlow(userId: String): Flow<List<ProfileRecentBook>> =
        activityRepository.observeRecentlyListened(userId, RECENT_BOOKS_LIMIT)

    private fun readyFromRow(
        userId: String,
        isOwn: Boolean,
        row: PublicProfileEntity,
        shelves: List<ProfileShelfSummary>,
        recentBooks: List<ProfileRecentBook>,
    ): UserProfileUiState.Ready =
        UserProfileUiState.Ready(
            userId = userId,
            isOwnProfile = isOwn,
            displayName = row.displayName,
            avatarColor = stableAvatarColorHex(userId),
            tagline = row.tagline,
            totalListenTimeMs = row.totalSecondsAllTime * MS_PER_SECOND,
            booksFinished = row.booksFinished,
            currentStreak = row.currentStreakDays,
            longestStreak = row.longestStreakDays,
            recentBooks = recentBooks,
            publicShelves = shelves,
        )

    private fun readyFromUser(
        userId: String,
        user: User,
        shelves: List<ProfileShelfSummary>,
        recentBooks: List<ProfileRecentBook>,
    ): UserProfileUiState.Ready =
        UserProfileUiState.Ready(
            userId = userId,
            isOwnProfile = true,
            displayName = user.displayName,
            avatarColor = stableAvatarColorHex(userId),
            tagline = user.tagline,
            totalListenTimeMs = 0L,
            booksFinished = 0,
            currentStreak = 0,
            longestStreak = 0,
            recentBooks = recentBooks,
            publicShelves = shelves,
        )

    private fun List<Shelf>.toSummaries(): List<ProfileShelfSummary> =
        map { ProfileShelfSummary(id = it.id.value, name = it.name, bookCount = it.bookCount) }

    private data class LoadRequest(
        val userId: String,
        val refreshCounter: Int,
    )

    companion object {
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /** How many books the "Recently listened" strip shows. */
        internal const val RECENT_BOOKS_LIMIT = 10
        private const val MS_PER_SECOND = 1_000L
    }
}
