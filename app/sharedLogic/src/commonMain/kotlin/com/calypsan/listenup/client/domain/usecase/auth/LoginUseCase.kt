package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
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
import kotlinx.datetime.TimeZone

/**
 * Authenticate the user against the server.
 *
 * Pre-flight validates the inputs, calls [AuthRepository.login], then on
 * success persists the session via [AuthSessionStore] (which flips
 * `AuthState` to `Authenticated`) and saves the returned user locally.
 *
 * Returns the contract-layer [AppResult] — call sites get typed
 * [com.calypsan.listenup.api.error.AuthError] variants on failure and the
 * fully-resolved [User] on success.
 */
open class LoginUseCase(
    private val authRepository: AuthRepository,
    private val authSession: AuthSessionStore,
    private val userRepository: UserRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
) {
    open suspend operator fun invoke(
        email: String,
        password: String,
    ): AppResult<User> {
        val trimmedEmail = email.trim()
        validate(trimmedEmail, password)?.let { return it }

        return authRepository
            .login(
                LoginRequest(
                    email = trimmedEmail,
                    password = password,
                    deviceInfo = deviceInfoProvider.current(),
                    timezone = TimeZone.currentSystemDefault().id,
                ),
            ).flatMap { session -> persistSession(session) }
    }

    private suspend fun persistSession(session: AuthSession): AppResult<User> {
        // Persist the user locally BEFORE flipping auth state. saveAuthTokens moves
        // AuthState to Authenticated, which immediately spins up the post-login
        // startup check (AppStartupViewModel); that check reads the current user, so
        // Room must already hold it or the check races an empty database and reads
        // null. Save first, then authenticate.
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
