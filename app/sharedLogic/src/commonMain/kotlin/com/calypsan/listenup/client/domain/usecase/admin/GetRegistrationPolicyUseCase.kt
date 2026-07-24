package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Retrieves the current [RegistrationPolicy] (`OPEN` / `APPROVAL_QUEUE` / `CLOSED`).
 */
open class GetRegistrationPolicyUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(): AppResult<RegistrationPolicy> = adminRepository.getRegistrationPolicy()
}
