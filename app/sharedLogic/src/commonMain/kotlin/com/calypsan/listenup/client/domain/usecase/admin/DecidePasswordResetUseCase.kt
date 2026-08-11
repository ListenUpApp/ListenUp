package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.dto.auth.PasswordResetDecisionOutcome
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Approves or denies a pending password-reset request. Approval mints the out-of-band
 * code the admin conveys to the requester; denial mints nothing.
 */
open class DecidePasswordResetUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(
        requestId: String,
        approved: Boolean,
    ): AppResult<PasswordResetDecisionOutcome> = adminRepository.decidePasswordReset(requestId, approved)
}
