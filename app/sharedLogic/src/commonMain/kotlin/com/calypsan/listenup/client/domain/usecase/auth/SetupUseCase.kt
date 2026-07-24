package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.client.core.ValidationField
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.model.toDomain
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession as AuthSessionStore
import com.calypsan.listenup.client.domain.repository.UserRepository

/**
 * Bootstrap the root user on a fresh server. Same orchestration shape as
 * [LoginUseCase]: validate inputs, call [AuthRepository.setup], persist
 * the returned session, save the user locally. Symmetric pre-flight
 * validation surfaces typed [ValidationError] values that the VM matches
 * against fields.
 */
open class SetupUseCase(
    private val authRepository: AuthRepository,
    private val authSession: AuthSessionStore,
    private val userRepository: UserRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
) {
    open suspend operator fun invoke(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): AppResult<User> {
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
            .setup(request)
            .flatMap { session -> persistSession(session) }
    }

    private suspend fun persistSession(session: AuthSession): AppResult<User> {
        // Persist the user locally BEFORE flipping auth state. saveAuthTokens moves
        // AuthState to Authenticated, which immediately spins up the post-login
        // startup check (AppStartupViewModel). That check reads the current user to
        // decide whether an admin still needs to create a library; if Room is empty
        // because we hadn't saved yet, it reads null and silently lands the admin in
        // the Shell instead of the Create-Library wizard. Save first, then authenticate.
        val user = session.user.toDomain()
        userRepository.saveUser(user)
        authSession.saveAuthTokens(
            access = session.accessToken,
            refresh = session.refreshToken,
            sessionId = session.sessionId.value,
            userId = session.user.id.value,
        )
        return AppResult.Success(user)
    }

    private fun validate(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): AppResult.Failure? =
        when {
            firstName.isBlank() -> {
                AppResult.Failure(ValidationError("First name is required", field = ValidationField.FIRST_NAME))
            }

            lastName.isBlank() -> {
                AppResult.Failure(ValidationError("Last name is required", field = ValidationField.LAST_NAME))
            }

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
