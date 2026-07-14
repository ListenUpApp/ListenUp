package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.validationError
import com.calypsan.listenup.client.core.ValidationField
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Creates a new invite code.
 *
 * Validates that the email is well-formed before creating. No display name is collected — the
 * invitee names their own account when they claim the invite.
 */
open class CreateInviteUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(
        email: String,
        role: String = "member",
        expiresInDays: Int = 7,
    ): AppResult<InviteInfo> {
        val trimmedEmail = email.trim()

        if (!isValidEmail(trimmedEmail)) {
            return validationError("Invalid email address", field = ValidationField.EMAIL)
        }

        return adminRepository.createInvite(
            email = trimmedEmail,
            role = role,
            expiresInDays = expiresInDays,
        )
    }

    private fun isValidEmail(email: String): Boolean = email.contains("@") && email.contains(".")
}
