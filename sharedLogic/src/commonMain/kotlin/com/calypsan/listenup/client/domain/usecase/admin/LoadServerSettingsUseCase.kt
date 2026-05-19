package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Loads server settings.
 *
 * Returns current server-wide settings including inbox workflow status.
 */
open class LoadServerSettingsUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(): AppResult<ServerSettings> = adminRepository.getServerSettings()
}
