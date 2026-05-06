package com.calypsan.listenup.client.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.domain.model.ProfileShelfSummary
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.model.UserProfile
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.usecase.profile.LoadUserProfileUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val logger = KotlinLogging.logger {}

/**
 * UI state for the [UserProfileViewModel].
 *
 * Single [Ready] variant covers both own-profile (local cache, stats stubbed at 0)
 * and other-user (server-fetched stats + recent books + shelves) — the screen
 * renders identically aside from the `isOwnProfile` admin-control toggle.
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
        val avatarType: String,
        val avatarValue: String?,
        val avatarColor: String,
        val tagline: String?,
        val localAvatarPath: String?,
        val avatarCacheBuster: Long,
        val totalListenTimeMs: Long,
        val booksFinished: Int,
        val currentStreak: Int,
        val longestStreak: Int,
        val recentBooks: List<ProfileRecentBook>,
        val publicShelves: List<ProfileShelfSummary>,
    ) : UserProfileUiState

    /** Load failed (only used for other-user fetches — own profile falls through to local cache). */
    data class Error(
        val message: String,
    ) : UserProfileUiState
}

/**
 * ViewModel for the User Profile screen.
 *
 * Own profile: observe [UserRepository.observeCurrentUser] reactively; all fields sourced
 * from the local User row, stats default to 0 until local stats computation lands.
 *
 * Other user: one-shot fetch via [LoadUserProfileUseCase]; if the avatar image is not
 * cached, download it and emit a refined [UserProfileUiState.Ready] with the local path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserProfileViewModel(
    private val loadUserProfileUseCase: LoadUserProfileUseCase,
    private val userRepository: UserRepository,
    private val imageRepository: ImageRepository,
    private val errorBus: ErrorBus,
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
            val localUser = userRepository.getCurrentUser()
            val isOwn = localUser != null && localUser.id.value == userId
            if (isOwn) {
                emitAll(ownProfileFlow())
            } else {
                emitAll(otherProfileFlow(userId))
            }
        }

    private fun ownProfileFlow(): Flow<UserProfileUiState> =
        userRepository.observeCurrentUser().map { user ->
            if (user == null) {
                UserProfileUiState.Error("No user data available")
            } else {
                buildOwnReady(user)
            }
        }

    private fun buildOwnReady(user: User): UserProfileUiState.Ready {
        val localAvatarPath = resolveLocalAvatarPath(user.id.value, user.avatarType)
        return UserProfileUiState.Ready(
            userId = user.id.value,
            isOwnProfile = true,
            displayName = user.displayName,
            avatarType = user.avatarType,
            avatarValue = user.avatarValue,
            avatarColor = user.avatarColor,
            tagline = user.tagline,
            localAvatarPath = localAvatarPath,
            avatarCacheBuster = user.updatedAtMs,
            // Stats default to 0 — local computation from ListeningEventEntity pending.
            totalListenTimeMs = 0,
            booksFinished = 0,
            currentStreak = 0,
            longestStreak = 0,
            recentBooks = emptyList(),
            publicShelves = emptyList(),
        )
    }

    private fun otherProfileFlow(userId: String): Flow<UserProfileUiState> =
        flow {
            when (val result = loadUserProfileUseCase(userId)) {
                is Success -> {
                    emitAll(otherProfileReadyFlow(result.data))
                }

                else -> {
                    logger.error { "Failed to load profile for: $userId" }
                    emit(UserProfileUiState.Error("Failed to load profile"))
                }
            }
        }

    private fun otherProfileReadyFlow(profile: UserProfile): Flow<UserProfileUiState> =
        flow {
            val booksWithLocalCovers = profile.recentBooks.mapWithLocalCovers()
            val cachedAvatarPath = resolveLocalAvatarPath(profile.userId, profile.avatarType)
            emit(buildOtherReady(profile, booksWithLocalCovers, cachedAvatarPath))

            val needsAvatarDownload =
                profile.avatarType == "image" &&
                    profile.avatarValue != null &&
                    cachedAvatarPath == null
            if (needsAvatarDownload) {
                val downloaded = tryDownloadAvatar(profile.userId)
                if (downloaded != null) {
                    emit(buildOtherReady(profile, booksWithLocalCovers, downloaded))
                }
            }
        }

    private fun buildOtherReady(
        profile: UserProfile,
        books: List<ProfileRecentBook>,
        avatarPath: String?,
    ): UserProfileUiState.Ready =
        UserProfileUiState.Ready(
            userId = profile.userId,
            isOwnProfile = false,
            displayName = profile.displayName,
            avatarType = profile.avatarType,
            avatarValue = profile.avatarValue,
            avatarColor = profile.avatarColor,
            tagline = profile.tagline,
            localAvatarPath = avatarPath,
            avatarCacheBuster = 0,
            totalListenTimeMs = profile.totalListenTimeMs,
            booksFinished = profile.booksFinished,
            currentStreak = profile.currentStreak,
            longestStreak = profile.longestStreak,
            recentBooks = books,
            publicShelves = profile.publicShelves,
        )

    private fun resolveLocalAvatarPath(
        userId: String,
        avatarType: String,
    ): String? =
        if (avatarType == "image" && imageRepository.userAvatarExists(userId)) {
            imageRepository.getUserAvatarPath(userId)
        } else {
            null
        }

    private fun List<ProfileRecentBook>.mapWithLocalCovers(): List<ProfileRecentBook> =
        map { book ->
            val id = BookId(book.bookId)
            val localCoverPath =
                if (imageRepository.bookCoverExists(id)) imageRepository.getBookCoverPath(id) else null
            book.copy(coverPath = localCoverPath)
        }

    private suspend fun tryDownloadAvatar(userId: String): String? =
        try {
            imageRepository.downloadUserAvatar(userId, forceRefresh = false)
            imageRepository.getUserAvatarPath(userId)
        } catch (cancel: kotlin.coroutines.cancellation.CancellationException) {
            throw cancel
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            @Suppress("DEPRECATION")
            errorBus.emit(ErrorMapper.map(e))
            logger.warn(e) { "Failed to download avatar for user $userId" }
            null
        }

    private data class LoadRequest(
        val userId: String,
        val refreshCounter: Int,
    )

    companion object {
        private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

/** Formats milliseconds to a short human-readable duration (e.g. "42h 30m"). */
fun formatListenTime(totalMs: Long): String {
    val hours = totalMs / MS_PER_HOUR
    val minutes = totalMs / MS_PER_MINUTE % MINUTES_PER_HOUR
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "0m"
    }
}

private const val MS_PER_MINUTE = 60_000L
private const val MS_PER_HOUR = 3_600_000L
private const val MINUTES_PER_HOUR = 60L
