package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserProfileDao
import com.calypsan.listenup.client.data.local.db.UserProfileEntity
import com.calypsan.listenup.client.data.remote.ProfileRpcFactory
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.domain.repository.ProfileRepository
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.error.ErrorMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private const val NO_CURRENT_USER_MESSAGE = "No current user"
private const val NO_CURRENT_USER_FOUND_MESSAGE = "No current user found"
private const val AUTO_VALUE = "auto"
private const val IMAGE_VALUE = "image"
private val logger = KotlinLogging.logger {}

/**
 * Implementation of [ProfileRepository].
 *
 * [refreshMyProfile] fetches the caller's own profile from the server via
 * [ProfileRpcFactory] / [com.calypsan.listenup.api.ProfileService] and writes
 * the result back into local Room ([UserDao], [UserProfileDao]). On success it
 * also triggers a force-refresh avatar download (when [avatarType] == `"image"`)
 * or deletes the local avatar file (when [avatarType] == `"auto"`).
 *
 * @property profileRpcFactory Produces the [com.calypsan.listenup.api.ProfileService] proxy.
 * @property userDao DAO for the current user's [com.calypsan.listenup.client.data.local.db.UserEntity] row.
 * @property userProfileDao DAO for the cached [UserProfileEntity] table.
 * @property avatarDownloadRepository Schedules / deletes local avatar files.
 */
class ProfileRepositoryImpl(
    private val profileRpcFactory: ProfileRpcFactory,
    private val userDao: UserDao,
    private val userProfileDao: UserProfileDao,
    private val avatarDownloadRepository: AvatarDownloadRepository,
) : ProfileRepository {
    // ── Own-profile read path ───────────────────────────────────────────────────

    /**
     * Fetch the caller's own profile from the Kotlin server via [ProfileService.getMyProfile],
     * then synchronously update both [UserDao] and [UserProfileDao] in Room so that
     * [com.calypsan.listenup.client.domain.repository.UserRepository.observeCurrentUser]
     * emits the latest server-side values.
     *
     * Avatar side-effect:
     * - `avatarType == "image"` → queue a force-refresh download.
     * - `avatarType == "auto"`  → delete any local avatar file.
     */
    override suspend fun refreshMyProfile(): AppResult<Unit> =
        withContext(IODispatcher) {
            val user =
                userDao.getCurrentUser() ?: run {
                    logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                    return@withContext AppResult.Failure(
                        ErrorMapper.map(IllegalStateException(NO_CURRENT_USER_MESSAGE)),
                    )
                }

            when (val result = rpcCall { profileRpcFactory.get().getMyProfile() }) {
                is AppResult.Success -> {
                    val profile = result.data
                    val userId = profile.userId.value
                    val now = currentEpochMilliseconds()

                    // Update tagline on the users row so observeCurrentUser sees it.
                    userDao.updateTagline(
                        userId = userId,
                        tagline = profile.tagline,
                        updatedAt = now,
                    )

                    // Update avatarType on the users row.
                    userDao.updateAvatar(
                        userId = userId,
                        avatarType = profile.avatarType,
                        avatarValue = null,
                        avatarColor = user.avatarColor,
                        updatedAt = now,
                    )

                    // Upsert the user_profiles cache so any component observing
                    // UserProfileRepository also sees the fresh values.
                    userProfileDao.upsert(
                        UserProfileEntity(
                            id = userId,
                            displayName = profile.displayName,
                            avatarType = profile.avatarType,
                            avatarValue = null,
                            avatarColor = user.avatarColor,
                            updatedAt = profile.updatedAt,
                        ),
                    )

                    // Avatar side-effect — Never-Stranded: failures are logged, not surfaced.
                    when (profile.avatarType) {
                        IMAGE_VALUE -> {
                            avatarDownloadRepository.queueAvatarForceRefresh(userId)
                            logger.info { "Queued force-refresh avatar download for own user $userId" }
                        }

                        AUTO_VALUE -> {
                            avatarDownloadRepository.deleteAvatar(userId)
                            logger.info { "Deleted local avatar for own user $userId (auto type)" }
                        }
                    }

                    logger.info { "Own profile refreshed from server for user $userId" }
                    AppResult.Success(Unit)
                }

                is AppResult.Failure -> {
                    result
                }
            }
        }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    /**
     * Run an RPC call, converting the contract-layer [WireAppResult] to the client [AppResult].
     * Re-throws [CancellationException]; all other throwables become [AppResult.Failure] via [ErrorMapper].
     */
    private suspend fun <T> rpcCall(block: suspend () -> WireAppResult<T>): AppResult<T> =
        try {
            when (val result = block()) {
                is WireAppResult.Success -> AppResult.Success(result.data)
                is WireAppResult.Failure -> AppResult.Failure(result.error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "Profile RPC failed" }
            AppResult.Failure(ErrorMapper.map(e))
        }
}
