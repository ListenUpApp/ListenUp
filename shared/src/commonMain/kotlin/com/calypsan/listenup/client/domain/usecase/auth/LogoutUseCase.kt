package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.data.sync.sse.PlaybackStateProvider
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.UserRepository
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

        playbackStateProvider?.clearPlayback()
        authSession.clearAuthTokens()
        userRepository.clearUsers()
        logger.info { "Local logout completed" }
        return AppResult.Success(Unit)
    }

    open suspend fun logoutLocally(): AppResult<Unit> {
        playbackStateProvider?.clearPlayback()
        authSession.clearAuthTokens()
        userRepository.clearUsers()
        logger.info { "Local-only logout completed" }
        return AppResult.Success(Unit)
    }
}
