package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Loads password-reset requests awaiting an admin decision.
 */
open class LoadPasswordResetRequestsUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(): AppResult<List<PasswordResetRequest>> =
        adminRepository.listPasswordResetRequests()
}
