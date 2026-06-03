package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.profile.PasswordChange
import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.UserDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.ProfileRpcFactory
import com.calypsan.listenup.client.data.remote.model.ApiResponse
import com.calypsan.listenup.client.domain.repository.ProfileEditRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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
fun interface AvatarUploader {
    /** POST [imageData] to the avatar endpoint; return success or a transport failure. */
    suspend fun upload(
        imageData: ByteArray,
        contentType: String,
    ): AppResult<Unit>
}

/**
 * Production [AvatarUploader] backed by [ApiClientFactory].
 */
fun avatarUploaderOf(clientFactory: ApiClientFactory): AvatarUploader =
    AvatarUploader { imageData, contentType ->
        try {
            val client = clientFactory.getClient()
            client
                .submitFormWithBinaryData(
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
                ).body<ApiResponse<Unit>>()
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppResult.Failure(ErrorMapper.map(e))
        }
    }

/**
 * Repository for profile editing operations.
 *
 * Mutations call [ProfileRpcFactory] to invoke [com.calypsan.listenup.api.ProfileService]
 * over RPC, then update local Room on success so the UI reflects the change immediately.
 * Avatar upload delegates to [AvatarUploader] (a REST multipart POST) and flips the local
 * [com.calypsan.listenup.client.data.local.db.UserEntity.avatarType] to `"image"` on success.
 */
class ProfileEditRepositoryImpl(
    private val userDao: UserDao,
    private val profileRpcFactory: ProfileRpcFactory,
    private val avatarUploader: AvatarUploader,
) : ProfileEditRepository {
    /**
     * Update the user's tagline.
     */
    override suspend fun updateTagline(tagline: String?): AppResult<Unit> =
        withContext(IODispatcher) {
            val user =
                userDao.getCurrentUser() ?: run {
                    logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                    return@withContext AppResult.Failure(
                        ErrorMapper.map(IllegalStateException(NO_CURRENT_USER_MESSAGE)),
                    )
                }
            rpcCall { profileRpcFactory.get().updateMyProfile(UpdateProfileRequest(tagline = tagline)) }
                .also { result ->
                    if (result is AppResult.Success) {
                        userDao.updateTagline(
                            userId = user.id.value,
                            tagline = tagline,
                            updatedAt = currentEpochMilliseconds(),
                        )
                        logger.info { "Tagline updated" }
                    }
                }.toUnit()
        }

    /**
     * Upload a new avatar image via multipart POST to the REST avatar endpoint.
     *
     * Flips [com.calypsan.listenup.client.data.local.db.UserEntity.avatarType] to `"image"` locally
     * on success so the UI can immediately switch to the avatar-render path. Avatar download
     * (Task 10) wires up the full URL; this task only persists the type change.
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

    /**
     * Update the user's name.
     */
    override suspend fun updateName(
        firstName: String,
        lastName: String,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            val user =
                userDao.getCurrentUser() ?: run {
                    logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                    return@withContext AppResult.Failure(
                        ErrorMapper.map(IllegalStateException(NO_CURRENT_USER_MESSAGE)),
                    )
                }
            val displayName = "$firstName $lastName".trim()
            rpcCall {
                profileRpcFactory.get().updateMyProfile(UpdateProfileRequest(displayName = displayName))
            }.also { result ->
                if (result is AppResult.Success) {
                    userDao.updateName(
                        userId = user.id.value,
                        firstName = firstName,
                        lastName = lastName,
                        displayName = displayName,
                        updatedAt = currentEpochMilliseconds(),
                    )
                    logger.info { "Name updated" }
                }
            }.toUnit()
        }

    /**
     * Change the user's password.
     *
     * Requires the current password for server-side verification.
     * Returns [com.calypsan.listenup.api.error.ProfileError.WrongPassword] on mismatch.
     */
    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): AppResult<Unit> =
        withContext(IODispatcher) {
            userDao.getCurrentUser() ?: run {
                logger.error { NO_CURRENT_USER_FOUND_MESSAGE }
                return@withContext AppResult.Failure(
                    ErrorMapper.map(IllegalStateException(NO_CURRENT_USER_MESSAGE)),
                )
            }
            rpcCall {
                profileRpcFactory.get().updateMyProfile(
                    UpdateProfileRequest(
                        password =
                            PasswordChange(
                                currentPassword = currentPassword,
                                newPassword = newPassword,
                            ),
                    ),
                )
            }.also { result ->
                if (result is AppResult.Success) {
                    logger.info { "Password changed successfully" }
                } else {
                    logger.error { "Password change failed" }
                }
            }.toUnit()
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

    /** Discard the typed data from a successful result, preserving failures. */
    private fun <T> AppResult<T>.toUnit(): AppResult<Unit> =
        when (this) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> this
        }
}
