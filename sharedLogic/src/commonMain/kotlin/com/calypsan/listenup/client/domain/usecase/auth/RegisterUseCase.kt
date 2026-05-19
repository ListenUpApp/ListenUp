package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession

/**
 * Register a new account.
 *
 * Pre-flight validates the inputs, calls [AuthRepository.register], then
 * folds over the sealed [RegisterResult]:
 *  - [RegisterResult.Authenticated] — open registration; we already have a
 *    session. Token persistence is deliberately *not* done here so this
 *    use case stays narrow; the caller (typically the register VM) checks
 *    the result and runs [LoginUseCase] follow-up if needed. (Open
 *    registration is uncommon in current configs.)
 *  - [RegisterResult.PendingApproval] — closed-with-queue instance. We
 *    transition `AuthState` to `PendingApproval` via
 *    [AuthSession.savePendingRegistration]; the pending-approval screen
 *    subscribes to the SSE status stream and prompts re-login on approval.
 *
 * The contract takes a single `displayName`; the UI still collects first +
 * last name separately, so we join them here. A future UX cleanup may
 * collapse the form to a single field.
 */
open class RegisterUseCase(
    private val authRepository: AuthRepository,
    private val authSession: AuthSession,
) {
    open suspend operator fun invoke(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): AppResult<RegisterResult> {
        val trimmedEmail = email.trim()
        val trimmedFirstName = firstName.trim()
        val trimmedLastName = lastName.trim()
        validate(trimmedEmail, password, trimmedFirstName, trimmedLastName)?.let { return it }

        val displayName = "$trimmedFirstName $trimmedLastName".trim()
        val request =
            RegisterRequest(
                email = trimmedEmail,
                password = password,
                displayName = displayName,
            )

        return authRepository
            .register(request)
            .flatMap { outcome -> persistOutcome(trimmedEmail, outcome) }
    }

    private suspend fun persistOutcome(
        email: String,
        outcome: RegisterResult,
    ): AppResult<RegisterResult> {
        if (outcome is RegisterResult.PendingApproval) {
            // No tokens yet — server queued the registration. Persist (userId, email)
            // so the pending-approval screen can subscribe to the SSE status stream
            // (keyed by userId) and display the user's email while waiting.
            authSession.savePendingRegistration(userId = outcome.userId.value, email = email)
        }
        return AppResult.Success(outcome)
    }

    private fun validate(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): AppResult.Failure? =
        when {
            !isValidEmail(email) -> {
                AppResult.Failure(ValidationError("Please enter a valid email address"))
            }

            password.length < PASSWORD_MIN -> {
                AppResult.Failure(
                    ValidationError("Password must be at least $PASSWORD_MIN characters"),
                )
            }

            firstName.isBlank() -> {
                AppResult.Failure(ValidationError("First name is required"))
            }

            lastName.isBlank() -> {
                AppResult.Failure(ValidationError("Last name is required"))
            }

            else -> {
                null
            }
        }

    private fun isValidEmail(email: String): Boolean {
        if (email.length > MAX_EMAIL_LENGTH) return false
        return EMAIL_REGEX.matches(email)
    }

    private companion object {
        const val MAX_EMAIL_LENGTH = 254
        val EMAIL_REGEX = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")
    }
}
