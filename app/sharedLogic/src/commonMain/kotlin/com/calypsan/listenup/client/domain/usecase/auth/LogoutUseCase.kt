package com.calypsan.listenup.client.domain.usecase.auth

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.LibraryResetHelper
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.playback.PlaybackStateProvider
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Logout flow — the single sign-out choke point every call site (shell nav, desktop nav,
 * Settings) routes through, so no path can partially clean up and leave stale state behind.
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
    private val libraryResetHelper: LibraryResetHelper,
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
     * Stop real-time sync first — otherwise the engine keeps reconnecting against the
     * now-unauthenticated endpoint (and could apply an in-flight firehose frame or drain an
     * outbox op after the rest of this sequence has already torn down) — then clear
     * every other piece of local state.
     */
    private suspend fun clearLocalState() {
        // 1. Stop the engine first: no drains or firehose applies may run while the rest of
        // this sequence tears down underneath them.
        syncRepository.disconnect()
        // 2. Drop every cached principal-bound RPC proxy / HttpClient so a re-login as a
        // different user in the same process can't reuse the previous session's socket.
        rpcCacheInvalidator.invalidateAll()
        playbackStateProvider?.clearPlayback()
        // 3. Clear library data, including any still-queued pending operations — a
        // signed-out user's unsent edits are deliberately discarded, not carried into
        // the next login (same or different user starts with a clean slate).
        libraryResetHelper.clearLibraryData(discardPendingOperations = true)
        // 4. Tokens die after the data they'd otherwise let a stray request re-fetch.
        authSession.clearAuthTokens()
        // 5. Finally drop the cached user rows themselves.
        userRepository.clearUsers()
    }
}
