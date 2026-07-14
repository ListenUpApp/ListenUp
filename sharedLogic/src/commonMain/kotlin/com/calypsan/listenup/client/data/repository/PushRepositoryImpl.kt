package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.PushRepository

/**
 * Production implementation of [PushRepository].
 *
 * Purely RPC-dispatched — there is no local mirror to keep in sync, since a push
 * token is a transient credential the platform SDK re-issues on rotation. [platform]
 * is a client-build fact (which platform this binary IS), not a per-call argument —
 * injected so the impl never hardcodes a platform value; the Android Koin module
 * binds [PushPlatform.ANDROID], iOS binds `IOS`.
 *
 * @property channel Dispatches the [PushService] RPC.
 * @property platform This build's [PushPlatform], supplied by the calling platform's DI.
 */
internal class PushRepositoryImpl(
    private val channel: RpcChannel<PushService>,
    private val platform: PushPlatform,
) : PushRepository {
    override suspend fun registerToken(token: String): AppResult<Unit> =
        channel.call { it.registerToken(token, platform) }

    override suspend fun unregisterToken(token: String): AppResult<Unit> =
        channel.call {
            it.unregisterToken(token)
        }

    override suspend fun sendTestNotification(): AppResult<Unit> = channel.call { it.sendTestNotification() }
}
