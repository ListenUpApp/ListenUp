@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.map
import com.calypsan.listenup.client.data.remote.InviteApiContract
import com.calypsan.listenup.client.data.remote.InviteClaimedUser
import com.calypsan.listenup.client.domain.model.InviteDetails
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import com.calypsan.listenup.client.data.remote.InviteDetails as ApiInviteDetails

/**
 * REST-backed [InviteRepository]. On `claimInvite` success we persist the
 * issued tokens and the new user locally so the call site doesn't need to
 * thread session machinery — invite acceptance is a registration + login
 * combined, and the side-effect lives with the operation.
 */
class InviteRepositoryImpl(
    private val inviteApi: InviteApiContract,
    private val authSession: AuthSession,
    private val userRepository: UserRepository,
) : InviteRepository {
    override suspend fun getInviteDetails(
        serverUrl: String,
        code: String,
    ): AppResult<InviteDetails> =
        inviteApi
            .getInviteDetails(serverUrl, code)
            .map { it.toDomain() }

    override suspend fun claimInvite(
        serverUrl: String,
        code: String,
        password: String,
    ): AppResult<User> {
        val result = inviteApi.claimInvite(serverUrl, code, password)
        return when (result) {
            is AppResult.Success -> {
                val response = result.data
                authSession.saveAuthTokens(
                    access = AccessToken(response.accessToken),
                    refresh = RefreshToken(response.refreshToken),
                    sessionId = response.sessionId,
                    userId = response.userId,
                )
                val user = response.user.toDomain()
                userRepository.saveUser(user)
                AppResult.Success(user)
            }

            is AppResult.Failure -> {
                result
            }
        }
    }
}

private fun ApiInviteDetails.toDomain(): InviteDetails =
    InviteDetails(
        name = name,
        email = email,
        serverName = serverName,
        invitedBy = invitedBy,
        valid = valid,
    )

@OptIn(ExperimentalTime::class)
private fun InviteClaimedUser.toDomain(): User =
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
