package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.client.core.ValidationField
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.model.toDomain
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.UserRepository

/**
 * Register a new account.
 *
 * Pre-flight validates the inputs, calls [AuthRepository.register], then
 * folds over the sealed [RegisterResult] — each outcome pins the matching
 * `AuthState` transition so top-level navigation reacts automatically:
 *  - [RegisterResult.Authenticated] — open registration; the server already
 *    issued a session. We persist it exactly like [LoginUseCase] (save the
 *    user locally *before* flipping auth state, so the post-login startup
 *    check never races an empty database), transitioning `AuthState` to
 *    `Authenticated`.
 *  - [RegisterResult.PendingApproval] — approval-queue instance. We
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
    private val userRepository: UserRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
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
                deviceInfo = deviceInfoProvider.current(),
            )

        return authRepository
            .register(request)
            .flatMap { outcome -> persistOutcome(trimmedEmail, outcome) }
    }

    private suspend fun persistOutcome(
        email: String,
        outcome: RegisterResult,
    ): AppResult<RegisterResult> {
        when (outcome) {
            is RegisterResult.Authenticated -> {
                // Open registration: the server issued a session immediately. Persist the
                // user BEFORE flipping auth state (saveAuthTokens moves AuthState to
                // Authenticated, which spins up the startup check that reads the current
                // user) — mirrors LoginUseCase so we don't race an empty database.
                val session = outcome.session
                userRepository.saveUser(session.user.toDomain())
                authSession.saveAuthTokens(
                    access = session.accessToken,
                    refresh = session.refreshToken,
                    sessionId = session.sessionId.value,
                    userId = session.user.id.value,
                )
            }

            is RegisterResult.PendingApproval -> {
                // No tokens yet — server queued the registration. Persist (userId, email)
                // so the pending-approval screen can subscribe to the SSE status stream
                // (keyed by userId) and display the user's email while waiting.
                authSession.savePendingRegistration(userId = outcome.userId.value, email = email)
            }
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
                AppResult.Failure(ValidationError("Please enter a valid email address", field = ValidationField.EMAIL))
            }

            password.length < PASSWORD_MIN -> {
                AppResult.Failure(
                    ValidationError(
                        "Password must be at least $PASSWORD_MIN characters",
                        field = ValidationField.PASSWORD,
                    ),
                )
            }

            firstName.isBlank() -> {
                AppResult.Failure(ValidationError("First name is required", field = ValidationField.FIRST_NAME))
            }

            lastName.isBlank() -> {
                AppResult.Failure(ValidationError("Last name is required", field = ValidationField.LAST_NAME))
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
