package com.calypsan.listenup.client.domain.usecase.admin

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.validationError
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.repository.AdminRepository

/**
 * Creates a new invite code.
 *
 * Validates that name is not blank and email is valid before creating.
 */
open class CreateInviteUseCase(
    private val adminRepository: AdminRepository,
) {
    open suspend operator fun invoke(
        name: String,
        email: String,
        role: String = "member",
        expiresInDays: Int = 7,
    ): AppResult<InviteInfo> {
        val trimmedName = name.trim()
        val trimmedEmail = email.trim()

        if (trimmedName.isBlank()) {
            return validationError("Name is required")
        }

        if (!isValidEmail(trimmedEmail)) {
            return validationError("Invalid email address")
        }

        return adminRepository.createInvite(
            name = trimmedName,
            email = trimmedEmail,
            role = role,
            expiresInDays = expiresInDays,
        )
    }

    private fun isValidEmail(email: String): Boolean = email.contains("@") && email.contains(".")
}
