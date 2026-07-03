package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.local.db.UserEntity
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.ProfileRpcFactory
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private const val NO_CURRENT_USER_MESSAGE = "No current user"
private const val NO_CURRENT_USER_FOUND_MESSAGE = "No current user found"
private const val AUTO_VALUE = "auto"
private const val IMAGE_VALUE = "image"
private const val AVATAR_UPLOAD_PATH = "/api/v1/profile/avatar"
private val logger = KotlinLogging.logger {}

/**
 * Performs the multipart avatar upload against the REST endpoint.
 *
 * Extracted as an interface so [ProfileEditRepositoryImpl] can be constructed in tests
 * without requiring a live [ApiClientFactory] (which is a final class).
 */
internal fun interface AvatarUploader {
    /** POST [imageData] to the avatar endpoint; return success or a transport failure. */
    suspend fun upload(
        imageData: ByteArray,
        contentType: String,
    ): AppResult<Unit>
}

/**
 * Production [AvatarUploader] backed by [ApiClientFactory].
 */
internal fun avatarUploaderOf(clientFactory: ApiClientFactory): AvatarUploader =
    AvatarUploader { imageData, contentType ->
        try {
            val client = clientFactory.getClient()
            val response =
                client.submitFormWithBinaryData(
                    url = AVATAR_UPLOAD_PATH,
                    formData =
                        formData {
                            append(
                                "file",
                                imageData,
                                Headers.build {
                                    append(HttpHeaders.ContentType, contentType)
                                    append(HttpHeaders.ContentDisposition, "filename=\"avatar\"")
                                },
                            )
                        },
                )
            // The server returns 204 No Content on success — do not attempt to deserialize
            // an empty body. Check the status code directly instead.
            if (response.status.isSuccess()) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(
                    ErrorMapper.map(IllegalStateException("avatar upload failed: ${response.status}")),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppResult.Failure(ErrorMapper.map(e))
        }
    }

/**
 * Repository for profile editing operations.
 *
 * [updateProfile] splits on whether a password change is present: a pure name/tagline change
 * routes through [OfflineEditor] (offline-first — writes Room and queues for durable replay on
 * reconnect); a password-bearing change stays fully synchronous online via
 * [updateProfileOnline], because password changes need immediate current-password validation
 * from the server and must never be queued.
 *
 * Avatar upload delegates to [AvatarUploader] (a REST multipart POST) and flips the local
 * [com.calypsan.listenup.client.data.local.db.UserEntity.avatarType] to `"image"` on success.
 * Avatar methods stay online-only, unchanged by the offline-first split.
 */
internal class ProfileEditRepositoryImpl(
    private val userDao: UserDao,
    private val profileRpcFactory: ProfileRpcFactory,
    private val avatarUploader: AvatarUploader,
    private val imageStorage: ImageStorage,
    private val offlineEditor: OfflineEditor,
) : ProfileEditRepository {
    /**
     * Persist changed profile text fields.
     *
     * A password-bearing change is routed to [updateProfileOnline] — always synchronous,
     * never queued, since the server must validate the current password immediately. A pure
     * name/tagline change (no password) is offline-first: it writes Room and enqueues a
     * durable pending op via [OfflineEditor], replaying to the server on reconnect.
     */
    override suspend fun updateProfile(
        firstName: String?,
        lastName: String?,
        tagline: String?,
        password: PasswordChange?,
    ): AppResult<Unit> =
        // Password requires immediate server validation — never queue it. A password-bearing
        // change stays fully synchronous online; a pure name/tagline change is offline-first.
        if (password != null) {
            updateProfileOnline(firstName, lastName, tagline, password)
        } else {
            updateProfileOffline(firstName, lastName, tagline)
        }

    /**
     * The offline-first path (no password): apply the optimistic name/tagline merge to Room and
     * enqueue a durable pending op via [OfflineEditor], replaying to the server on reconnect.
     */
    private suspend fun updateProfileOffline(
        firstName: String?,
        lastName: String?,
        tagline: String?,
    ): AppResult<Unit> {
        val user =
            userDao.getCurrentUser()
                ?: run {
                    logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                    return AppResult.Failure(ErrorMapper.map(IllegalStateException(NO_CURRENT_USER_MESSAGE)))
                }
        val displayName = mergedDisplayName(firstName, lastName, user)
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(
            OutboxChannels.Profile,
            entityId = user.id.value,
            patch = UpdateProfileRequest(displayName = displayName, tagline = tagline),
        ) {
            if (firstName != null || lastName != null) {
                userDao.updateName(
                    userId = user.id.value,
                    firstName = firstName ?: user.firstName ?: "",
                    lastName = lastName ?: user.lastName ?: "",
                    displayName = displayName ?: user.displayName,
                    updatedAt = now,
                )
            }
            if (tagline != null) {
                userDao.updateTagline(userId = user.id.value, tagline = tagline.ifEmpty { null }, updatedAt = now)
            }
        }
    }

    /**
     * The password-bearing path: consolidates name, tagline, and password into one
     * [com.calypsan.listenup.api.ProfileService.updateMyProfile] RPC call, then updates local
     * Room on success so the UI reflects changes immediately. Always synchronous online — this
     * preserves the pre-split behavior for the password-bearing case, where immediate
     * current-password validation from the server must never be queued.
     */
    private suspend fun updateProfileOnline(
        firstName: String?,
        lastName: String?,
        tagline: String?,
        password: PasswordChange?,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            val user =
                userDao.getCurrentUser() ?: run {
                    logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                    return@withContext AppResult.Failure(
                        ErrorMapper.map(IllegalStateException(NO_CURRENT_USER_MESSAGE)),
                    )
                }

            // Build the displayName for the RPC only when a name change is requested.
            val displayName = mergedDisplayName(firstName, lastName, user)

            rpcCall {
                profileRpcFactory.get().updateMyProfile(
                    UpdateProfileRequest(
                        displayName = displayName,
                        tagline = tagline,
                        password = password,
                    ),
                )
            }.also { result ->
                if (result is AppResult.Success) {
                    val now = currentEpochMilliseconds()
                    if (firstName != null || lastName != null) {
                        userDao.updateName(
                            userId = user.id.value,
                            firstName = firstName ?: user.firstName ?: "",
                            lastName = lastName ?: user.lastName ?: "",
                            displayName = displayName ?: user.displayName,
                            updatedAt = now,
                        )
                        logger.info { "Name updated in local cache" }
                    }
                    if (tagline != null) {
                        userDao.updateTagline(
                            userId = user.id.value,
                            tagline = tagline.ifEmpty { null },
                            updatedAt = now,
                        )
                        logger.info { "Tagline updated in local cache" }
                    }
                    if (password != null) {
                        logger.info { "Password changed successfully" }
                    }
                }
            }.toUnit()
        }

    /**
     * Upload a new avatar image via multipart POST to the REST avatar endpoint.
     *
     * Flips [com.calypsan.listenup.client.data.local.db.UserEntity.avatarType] to `"image"` locally
     * on success so the UI can immediately switch to the avatar-render path.
     */
    override suspend fun uploadAvatar(
        imageData: ByteArray,
        contentType: String,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            val user =
                userDao.getCurrentUser() ?: run {
                    logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                    return@withContext AppResult.Failure(
                        ErrorMapper.map(IllegalStateException(NO_CURRENT_USER_MESSAGE)),
                    )
                }
            when (val uploadResult = avatarUploader.upload(imageData, contentType)) {
                is AppResult.Success -> {
                    userDao.updateAvatar(
                        userId = user.id.value,
                        avatarType = IMAGE_VALUE,
                        avatarValue = null,
                        avatarColor = user.avatarColor,
                        updatedAt = currentEpochMilliseconds(),
                    )
                    when (val cached = imageStorage.saveUserAvatar(user.id.value, imageData)) {
                        is AppResult.Failure -> logger.warn { "Avatar local cache write failed: ${cached.error}" }
                        is AppResult.Success -> Unit
                    }
                    logger.info { "Avatar uploaded, local type flipped to image" }
                    AppResult.Success(Unit)
                }

                is AppResult.Failure -> {
                    logger.error { "Avatar upload failed: ${uploadResult.message}" }
                    uploadResult
                }
            }
        }

    /**
     * Revert to auto-generated avatar.
     */
    override suspend fun revertToAutoAvatar(): AppResult<Unit> =
        withContext(IODispatcher) {
            val user =
                userDao.getCurrentUser() ?: run {
                    logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                    return@withContext AppResult.Failure(
                        ErrorMapper.map(IllegalStateException(NO_CURRENT_USER_MESSAGE)),
                    )
                }
            rpcCall { profileRpcFactory.get().updateMyProfile(UpdateProfileRequest(avatarType = AUTO_VALUE)) }
                .also { result ->
                    if (result is AppResult.Success) {
                        userDao.updateAvatar(
                            userId = user.id.value,
                            avatarType = AUTO_VALUE,
                            avatarValue = null,
                            avatarColor = user.avatarColor,
                            updatedAt = currentEpochMilliseconds(),
                        )
                        logger.info { "Reverted to auto avatar" }
                    }
                }.toUnit()
        }

    // ── Plumbing ────────────────────────────────────────────────────────────────

    /**
     * Collapses an optional first/last-name change into a display name, merging each unchanged
     * half from [user], or null when neither name field changed (so the patch leaves it untouched).
     */
    private fun mergedDisplayName(
        firstName: String?,
        lastName: String?,
        user: UserEntity,
    ): String? =
        if (firstName != null || lastName != null) {
            listOfNotNull(firstName ?: user.firstName, lastName ?: user.lastName)
                .joinToString(" ")
                .ifBlank { null }
        } else {
            null
        }

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

    /** Discard the typed data from a successful result, preserving failures. */
    private fun <T> AppResult<T>.toUnit(): AppResult<Unit> =
        when (this) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> this
        }
}
