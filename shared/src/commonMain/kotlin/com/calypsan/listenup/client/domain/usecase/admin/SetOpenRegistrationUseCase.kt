package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Enables or disables open registration.
 */
open class SetOpenRegistrationUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(enabled: Boolean): AppResult<Unit> = adminRepository.setOpenRegistration(enabled)
}
