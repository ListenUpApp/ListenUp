package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Sets the server [RegistrationPolicy] (`OPEN` / `APPROVAL_QUEUE` / `CLOSED`).
 */
open class SetRegistrationPolicyUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(policy: RegistrationPolicy): AppResult<Unit> =
        adminRepository.setRegistrationPolicy(policy)
}
