@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.remote.AuthApiContract
import com.calypsan.listenup.client.data.remote.AuthUser
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.LoginResult
import com.calypsan.listenup.client.domain.repository.RegistrationResult
import com.calypsan.listenup.client.domain.repository.RegistrationStatus
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Implementation of AuthRepository that wraps AuthApiContract.
 *
 * Maps data layer responses to domain types, keeping network
 * concerns in the data layer while exposing clean domain
 * results to use cases.
 *
 * @property authApi Data layer API for authentication operations
 */
class AuthRepositoryImpl(
    private val authApi: AuthApiContract,
) : AuthRepository {
    override suspend fun login(
        email: String,
        password: String,
    ): LoginResult {
        val response = authApi.login(email, password)

        return LoginResult(
            accessToken = AccessToken(response.accessToken),
            refreshToken = RefreshToken(response.refreshToken),
            sessionId = response.sessionId,
            userId = response.userId,
            user = response.user.toDomain(),
        )
    }

    override suspend fun register(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): RegistrationResult {
        val response = authApi.register(email, password, firstName, lastName)

        return RegistrationResult(
            userId = response.userId,
            message = response.message,
        )
    }

    override suspend fun logout(sessionId: String) {
        authApi.logout(sessionId)
    }

    override suspend fun setup(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
    ): LoginResult {
        val response = authApi.setup(email, password, firstName, lastName)

        return LoginResult(
            accessToken = AccessToken(response.accessToken),
            refreshToken = RefreshToken(response.refreshToken),
            sessionId = response.sessionId,
            userId = response.userId,
            user = response.user.toDomain(),
        )
    }

    override suspend fun checkRegistrationStatus(userId: String): RegistrationStatus {
        val response = authApi.checkRegistrationStatus(userId)

        return RegistrationStatus(
            userId = response.userId,
            status = response.status,
            approved = response.approved,
        )
    }
}

/**
 * Convert AuthUser API response to User domain model.
 */
@OptIn(ExperimentalTime::class)
private fun AuthUser.toDomain(): User =
    User(
        id = UserId(id),
        email = email,
        displayName = displayName,
        firstName = firstName.ifEmpty { null },
        lastName = lastName.ifEmpty { null },
        isAdmin = isRoot,
        avatarType = avatarType,
        avatarValue = avatarValue,
        avatarColor = avatarColor,
        tagline = null, // Not provided in auth response
        createdAtMs = Instant.parse(createdAt).toEpochMilliseconds(),
        updatedAtMs = Instant.parse(updatedAt).toEpochMilliseconds(),
    )
