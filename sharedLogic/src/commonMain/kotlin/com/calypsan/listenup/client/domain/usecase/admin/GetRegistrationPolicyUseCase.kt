package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Retrieves the current registration policy as a simple open/closed boolean.
 *
 * Returns `true` when open registration is enabled, `false` otherwise.
 */
open class GetRegistrationPolicyUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(): AppResult<Boolean> = adminRepository.getRegistrationPolicy()
}
