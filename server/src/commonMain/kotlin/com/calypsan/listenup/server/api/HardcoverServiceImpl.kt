package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.dto.hardcover.HardcoverConnection
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkPrompt
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.hardcover.HardcoverLinker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The caller's Hardcover connection over RPC — a thin, user-scoped face on [HardcoverLinker], which
 * owns the sign-in poll, the per-user state and the disconnect. Every method acts on the calling
 * user only, so one user can never start, watch or end another's connection.
 *
 * [clientIdConfigured] is false when the operator hasn't set `hardcover.clientId`: the integration
 * is off, and [startLink] answers [HardcoverError.NotConfigured] before anything reaches Hardcover.
 * A caller with no connection then watches [HardcoverConnection.NotOffered], so clients hide the
 * entry instead of offering a dead end. Watching and disconnecting still work, so a connection made
 * before the id was cleared can still be seen and ended.
 *
 * Route handlers call [copyWith] to bind each connection to the authenticated principal. Without
 * one, every method fails closed with [AuthError.PermissionDenied].
 */
class HardcoverServiceImpl(
    private val linker: HardcoverLinker,
    private val clientIdConfigured: Boolean,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : HardcoverService {
    /** Returns a copy scoped to [provider]. The RPC mount calls this per connection. */
    fun copyWith(provider: PrincipalProvider): HardcoverServiceImpl =
        HardcoverServiceImpl(linker, clientIdConfigured, provider)

    override suspend fun startLink(): AppResult<HardcoverLinkPrompt> {
        val userId = callerId() ?: return permissionDenied()
        if (!clientIdConfigured) return AppResult.Failure(HardcoverError.NotConfigured())
        return linker.start(userId)
    }

    override fun observeConnection(): Flow<RpcEvent<HardcoverConnection>> =
        flow {
            val userId = callerId()
            if (userId == null) {
                emit(RpcEvent.Error(AuthError.PermissionDenied()))
                return@flow
            }
            emitAll(linker.observe(userId).map { RpcEvent.Data(it.offeredOrNot()) })
        }

    override suspend fun disconnect(): AppResult<Unit> {
        val userId = callerId() ?: return permissionDenied()
        linker.disconnect(userId)
        return AppResult.Success(Unit)
    }

    private fun HardcoverConnection.offeredOrNot(): HardcoverConnection =
        if (!clientIdConfigured && this is HardcoverConnection.NotConnected) HardcoverConnection.NotOffered else this

    private fun callerId(): String? = principal.current()?.userId?.value

    private fun permissionDenied(): AppResult.Failure = AppResult.Failure(AuthError.PermissionDenied())
}
