package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
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
        authSession.saveAuthTokens(
            access = session.accessToken,
            refresh = session.refreshToken,
            sessionId = session.sessionId.value,
            userId = session.user.id.value,
        )
        val user = session.user.toDomain()
        userRepository.saveUser(user)
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
                AppResult.Failure(ValidationError("First name is required"))
            }

            lastName.isBlank() -> {
                AppResult.Failure(ValidationError("Last name is required"))
            }

            !isValidEmail(email) -> {
                AppResult.Failure(ValidationError("Please enter a valid email address"))
            }

            password.length < PASSWORD_MIN -> {
                AppResult.Failure(
                    ValidationError("Password must be at least $PASSWORD_MIN characters"),
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
