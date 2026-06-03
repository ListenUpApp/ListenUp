package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Logout flow.
 *
 * Server-side session revocation is best-effort — if it fails (network
 * down, expired token), local state still clears. Local-only logout is
 * the path for "you're already offline / your token's already dead;
 * just stop pretending to be logged in."
 *
 * Server's `AuthServiceAuthed.logout()` reads the session id from the
 * bearer JWT, so the client doesn't pass one explicitly.
 */
open class LogoutUseCase(
    private val authRepository: AuthRepository,
    private val authSession: AuthSession,
    private val userRepository: UserRepository,
    private val syncRepository: SyncRepository,
    private val rpcCacheInvalidator: RpcCacheInvalidator,
    private val playbackStateProvider: PlaybackStateProvider? = null,
) {
    open suspend operator fun invoke(): AppResult<Unit> {
        if (authSession.isAuthenticated()) {
            authRepository
                .logout()
                .onFailure { error ->
                    logger.warn { "Server-side logout failed (continuing with local logout): $error" }
                }
        }

        clearLocalState()
        logger.info { "Local logout completed" }
        return AppResult.Success(Unit)
    }

    open suspend fun logoutLocally(): AppResult<Unit> {
        clearLocalState()
        logger.info { "Local-only logout completed" }
        return AppResult.Success(Unit)
    }

    /**
     * Stop real-time sync first — otherwise the engine keeps reconnecting against
     * the now-unauthenticated endpoint — then clear local auth/user/playback state.
     */
    private suspend fun clearLocalState() {
        syncRepository.disconnect()
        // Drop every cached principal-bound RPC proxy / HttpClient so a re-login as a
        // different user in the same process can't reuse the previous session's socket.
        rpcCacheInvalidator.invalidateAll()
        playbackStateProvider?.clearPlayback()
        authSession.clearAuthTokens()
        userRepository.clearUsers()
    }
}
