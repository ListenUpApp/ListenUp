@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.remote.AuthUser
import com.calypsan.listenup.client.data.remote.InviteApiContract
import com.calypsan.listenup.client.domain.model.InviteDetails
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.LoginResult
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import com.calypsan.listenup.client.data.remote.InviteDetails as ApiInviteDetails

/**
 * Implementation of InviteRepository that wraps InviteApiContract.
 *
 * Maps data layer responses to domain types.
 *
 * @property inviteApi Data layer API for invite operations
 */
class InviteRepositoryImpl(
    private val inviteApi: InviteApiContract,
) : InviteRepository {
    override suspend fun getInviteDetails(
        serverUrl: String,
        code: String,
    ): InviteDetails {
        val response = inviteApi.getInviteDetails(serverUrl, code)
        return response.toDomain()
    }

    override suspend fun claimInvite(
        serverUrl: String,
        code: String,
        password: String,
    ): LoginResult {
        val response = inviteApi.claimInvite(serverUrl, code, password)

        return LoginResult(
            accessToken = AccessToken(response.accessToken),
            refreshToken = RefreshToken(response.refreshToken),
            sessionId = response.sessionId,
            userId = response.userId,
            user = response.user.toDomain(),
        )
    }
}

/**
 * Convert API InviteDetails to domain InviteDetails.
 */
private fun ApiInviteDetails.toDomain(): InviteDetails =
    InviteDetails(
        name = name,
        email = email,
        serverName = serverName,
        invitedBy = invitedBy,
        valid = valid,
    )

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
        tagline = null,
        createdAtMs = Instant.parse(createdAt).toEpochMilliseconds(),
        updatedAtMs = Instant.parse(updatedAt).toEpochMilliseconds(),
    )
