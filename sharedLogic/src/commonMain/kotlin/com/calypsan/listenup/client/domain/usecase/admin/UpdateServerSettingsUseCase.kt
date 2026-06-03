package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Updates server settings.
 *
 * Allows admins to update server-wide settings like the server name
 * and inbox workflow toggle.
 */
open class UpdateServerSettingsUseCase(
    private val adminRepository: AdminRepository,
) {
    /**
     * Update inbox enabled setting only (backwards compatible).
     */
    open suspend operator fun invoke(inboxEnabled: Boolean): AppResult<ServerSettings> =
        adminRepository.updateServerSettings(inboxEnabled = inboxEnabled)

    /**
     * Update server name only.
     */
    open suspend fun updateServerName(serverName: String): AppResult<ServerSettings> =
        adminRepository.updateServerSettings(serverName = serverName)
}
