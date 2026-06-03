package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Loads all users awaiting approval.
 */
open class LoadPendingUsersUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(): AppResult<List<AdminUserInfo>> = adminRepository.getPendingUsers()
}
