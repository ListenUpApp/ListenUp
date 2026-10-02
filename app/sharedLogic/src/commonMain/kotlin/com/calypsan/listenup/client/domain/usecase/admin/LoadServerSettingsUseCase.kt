package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Loads the server-identity settings (server name + optional remote URL).
 */
open class LoadServerSettingsUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(): AppResult<ServerSettings> = adminRepository.getServerSettings()

    /** Every outside rating source, with its enabled flag and last-fetch health. */
    open suspend fun ratingSources(): AppResult<List<RatingSourceStatus>> = adminRepository.getRatingSources()

    /** Admin → Hardcover: the API token's state and the metadata switch. */
    open suspend fun hardcoverSource(): AppResult<HardcoverSourceStatus> = adminRepository.getHardcoverSource()
}
