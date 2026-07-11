package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.model.toDomain
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.repository.AuthSession as ClientAuthSession

/**
 * Thin adapter over the anonymous invite surface — dispatches lookup/claim through [channel], an
 * `RpcPolicy.Public` [RpcChannel] over [InviteServicePublic]. On a successful claim the issued user
 * is saved to the local store and the issued session is persisted via
 * [ClientAuthSession.saveAuthTokens], landing the user logged-in exactly like login does.
 *
 * The channel folds transport faults into a typed [AppResult.Failure] and re-raises cancellation, so
 * callers never see a raw exception across the wire.
 */
internal class InviteRepositoryImpl(
    private val channel: RpcChannel<InviteServicePublic>,
    private val authSession: ClientAuthSession,
    private val userRepository: UserRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
) : InviteRepository {
    override suspend fun lookupInvite(code: String): AppResult<InvitePreview> = channel.call { it.lookupInvite(code) }

    override suspend fun claimInvite(
        code: String,
        password: String,
        displayName: String?,
    ): AppResult<AuthSession> {
        val result =
            channel.call { it.claimInvite(code, password, displayName, deviceInfoProvider.current()) }
        if (result is AppResult.Success) {
            val session = result.data
            // Persist the user locally BEFORE flipping auth state. saveAuthTokens moves
            // AuthState to Authenticated, which immediately spins up the post-login startup
            // check; that check reads the current user, so Room must already hold it or the
            // check races an empty database and reads null. Save first, then authenticate —
            // mirrors LoginUseCase.persistSession.
            userRepository.saveUser(session.user.toDomain())
            authSession.saveAuthTokens(
                access = session.accessToken,
                refresh = session.refreshToken,
                sessionId = session.sessionId.value,
                userId = session.user.id.value,
            )
        }
        return result
    }
}
