package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Approves a pending user registration.
 */
open class ApproveUserUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(userId: String): AppResult<AdminUserInfo> = adminRepository.approveUser(userId)
}
