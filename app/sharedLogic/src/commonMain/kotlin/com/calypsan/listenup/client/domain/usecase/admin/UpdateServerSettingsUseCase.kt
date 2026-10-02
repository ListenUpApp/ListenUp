package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Updates server-identity settings.
 *
 * Allows admins to update the server display name, public remote URL, inbox quarantine gate,
 * and push-notifications toggle.
 */
open class UpdateServerSettingsUseCase(
    private val adminRepository: AdminRepository,
) {
    /** Update server name only. */
    open suspend fun updateServerName(serverName: String): AppResult<ServerSettings> =
        adminRepository.updateServerSettings(serverName = serverName)

    /** Update remote URL only (empty string clears). */
    open suspend fun updateRemoteUrl(remoteUrl: String): AppResult<ServerSettings> =
        adminRepository.updateServerSettings(remoteUrl = remoteUrl)

    /** Enable or disable the server-wide inbox quarantine gate. */
    open suspend fun updateHoldNewBooksForReview(enabled: Boolean): AppResult<ServerSettings> =
        adminRepository.updateServerSettings(holdNewBooksForReview = enabled)

    /** Enable or disable server-wide push notifications (FCM relay delivery). */
    open suspend fun updatePushNotificationsEnabled(enabled: Boolean): AppResult<ServerSettings> =
        adminRepository.updateServerSettings(pushNotificationsEnabled = enabled)

    /** Switches [source] on or off; disabling flips `enabled` on each of its existing rows. */
    open suspend fun setRatingSourceEnabled(
        source: ExternalRatingSource,
        enabled: Boolean,
    ): AppResult<List<RatingSourceStatus>> = adminRepository.setRatingSourceEnabled(source, enabled)

    /** Sends a Hardcover API token to be checked and stored on the server. */
    open suspend fun setHardcoverApiToken(token: String): AppResult<HardcoverSourceStatus> =
        adminRepository.setHardcoverApiToken(token)

    /** Removes the server's Hardcover API token. */
    open suspend fun clearHardcoverApiToken(): AppResult<HardcoverSourceStatus> =
        adminRepository.clearHardcoverApiToken()

    /** Switches Hardcover metadata on or off. */
    open suspend fun setHardcoverMetadataEnabled(enabled: Boolean): AppResult<HardcoverSourceStatus> =
        adminRepository.setHardcoverMetadataEnabled(enabled)
}
