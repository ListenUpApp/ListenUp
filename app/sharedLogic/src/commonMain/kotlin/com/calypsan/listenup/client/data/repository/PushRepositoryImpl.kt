package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.PushRepository

/**
 * Production implementation of [PushRepository].
 *
 * Purely RPC-dispatched — there is no local mirror to keep in sync, since a push
 * token is a transient credential the platform SDK re-issues on rotation.
 *
 * The [PushPlatform] arrives with the token rather than being injected here. A build with no push
 * hook has no platform to inject, and demanding one anyway made this class unconstructable on web
 * and desktop — which surfaced as a swallowed `Refresh refetch failed` on every refresh.
 *
 * @property channel Bounded, self-healing dispatch for the authed [PushService].
 */
internal class PushRepositoryImpl(
    private val channel: RpcChannel<PushService>,
    private val publicAuthChannel: RpcChannel<AuthServicePublic>,
) : PushRepository {
    override suspend fun registerToken(
        token: String,
        platform: PushPlatform,
    ): AppResult<Unit> = channel.call { it.registerToken(token, platform) }

    override suspend fun unregisterToken(token: String): AppResult<Unit> = channel.call { it.unregisterToken(token) }

    override suspend fun sendTestNotification(): AppResult<Unit> = channel.call { it.sendTestNotification() }

    override suspend fun registerRegistrationWatchToken(
        userId: String,
        token: String,
        platform: PushPlatform,
    ): AppResult<Unit> = publicAuthChannel.call { it.registerRegistrationWatchToken(userId, token, platform) }
}
