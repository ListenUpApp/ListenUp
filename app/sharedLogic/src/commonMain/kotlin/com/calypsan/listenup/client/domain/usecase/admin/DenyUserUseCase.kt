package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Denies a pending user registration.
 */
open class DenyUserUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(userId: String): AppResult<Unit> = adminRepository.denyUser(userId)
}
