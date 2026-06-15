package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Updates server-identity settings.
 *
 * Allows admins to update the server display name, public remote URL, and inbox quarantine gate.
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
    open suspend fun updateInboxEnabled(enabled: Boolean): AppResult<ServerSettings> =
        adminRepository.updateServerSettings(inboxEnabled = enabled)
}
