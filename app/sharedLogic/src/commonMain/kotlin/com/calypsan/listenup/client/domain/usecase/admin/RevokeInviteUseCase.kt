package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Revokes/deletes an invite.
 */
open class RevokeInviteUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(inviteId: String): AppResult<Unit> = adminRepository.deleteInvite(inviteId)
}
