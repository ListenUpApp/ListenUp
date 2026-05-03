package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.RegistrationResult
import com.calypsan.listenup.client.core.validationError

/**
 * Use case for user registration.
 *
 * Encapsulates all business logic for registration:
 * - Email validation (format, length)
 * - Password validation (minimum 8 characters)
 * - First/last name validation (non-blank)
 * - API call orchestration
 * - Pending registration state storage
 *
 * After successful registration, the AuthState transitions to PendingApproval.
 * The user must wait for admin approval before they can log in.
 *
 * Follows the operator invoke pattern for clean call-site syntax:
 * ```kotlin
 * when (val result = registerUseCase(email, password, firstName, lastName)) {
 *     is Success -> navigateToPendingApproval()
 *     is Failure -> showError(result.message)
 * }
 * ```
 */
open class RegisterUseCase(
    private val authRepository: AuthRepository,
    private val authSession: AuthSession,
) {
    /**
     * Execute registration with the provided details.
     *
     * @param email User's email address (will be trimmed)
     * @param password User's password (min 8 characters)
     * @param firstName User's first name
     * @param lastName User's last name
     * @return Result containing RegistrationResult on success, or an error on failure
     */
    open suspend operator fun invoke(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): AppResult<RegistrationResult> {
        val trimmedEmail = email.trim()
        val trimmedFirstName = firstName.trim()
        val trimmedLastName = lastName.trim()

        // Validate email format
        if (!isValidEmail(trimmedEmail)) {
            return validationError("Please enter a valid email address")
        }

        // Validate password length
        if (password.length < PASSWORD_MIN) {
            return validationError("Password must be at least $PASSWORD_MIN characters")
        }

        // Validate first name
        if (trimmedFirstName.isBlank()) {
            return validationError("First name is required")
        }

        // Validate last name
        if (trimmedLastName.isBlank()) {
            return validationError("Last name is required")
        }

        // Perform registration
        return suspendRunCatching {
            val result =
                authRepository.register(
                    email = trimmedEmail,
                    password = password,
                    firstName = trimmedFirstName,
                    lastName = trimmedLastName,
                )

            // Save pending registration state - this will:
            // 1. Persist credentials securely for auto-login after approval
            // 2. Update AuthState to PendingApproval
            // 3. Trigger navigation to PendingApprovalScreen
            authSession.savePendingRegistration(
                userId = result.userId,
                email = trimmedEmail,
                password = password,
            )

            result
        }
    }

    /**
     * Email validation using a practical regex pattern.
     *
     * Validates:
     * - Has local part before @
     * - Has domain part after @
     * - Domain has at least one dot with TLD
     * - Reasonable length limit (RFC 5321)
     */
    private fun isValidEmail(email: String): Boolean {
        if (email.length > MAX_EMAIL_LENGTH) return false
        return EMAIL_REGEX.matches(email)
    }

    private companion object {
        const val MAX_EMAIL_LENGTH = 254
        val EMAIL_REGEX = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    }
}
