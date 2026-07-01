package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.InviteRpcFactory
import com.calypsan.listenup.client.device.DeviceInfoProvider
import com.calypsan.listenup.client.domain.model.toDomain
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import com.calypsan.listenup.client.domain.repository.AuthSession as ClientAuthSession

private val logger = KotlinLogging.logger {}

/**
 * Thin adapter over [InviteRpcFactory] — dispatches lookup/claim through the
 * public RPC proxy. On a successful claim the issued user is saved to the local
 * store and the issued session is persisted via [ClientAuthSession.saveAuthTokens],
 * landing the user logged-in exactly like login does. Transport failures collapse
 * to `AppResult.Failure(InternalError)` so callers never see a raw exception across
 * the wire.
 *
 * Per kotlinx.coroutines convention, `CancellationException` is re-thrown.
 */
internal class InviteRepositoryImpl(
    private val rpc: InviteRpcFactory,
    private val authSession: ClientAuthSession,
    private val userRepository: UserRepository,
    private val deviceInfoProvider: DeviceInfoProvider,
) : InviteRepository {
    override suspend fun lookupInvite(code: String): AppResult<InvitePreview> =
        catching("lookupInvite") { rpc.publicService().lookupInvite(code) }

    override suspend fun claimInvite(
        code: String,
        password: String,
        displayName: String?,
    ): AppResult<AuthSession> {
        val result =
            catching("claimInvite") {
                rpc.publicService().claimInvite(code, password, displayName, deviceInfoProvider.current())
            }
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

    private suspend inline fun <T> catching(
        op: String,
        block: () -> AppResult<T>,
    ): AppResult<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "invite $op failed at the transport boundary" }
            AppResult.Failure(InternalError())
        }
}
