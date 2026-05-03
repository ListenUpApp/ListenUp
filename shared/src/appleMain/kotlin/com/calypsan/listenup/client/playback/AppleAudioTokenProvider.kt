@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.data.remote.AuthApi
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * iOS implementation of AudioTokenProvider.
 *
 * Design:
 * - Token is cached in a volatile field (non-blocking reads)
 * - Refresh happens proactively on a schedule AND reactively on 401
 * - Uses the existing auth infrastructure for actual refresh
 *
 * iOS-specific notes:
 * - URLSession delegates handle auth headers separately
 * - This class provides the token; iOS networking code reads it
 */
class AppleAudioTokenProvider(
    private val authSession: AuthSession,
    private val authApi: AuthApi,
    private val scope: CoroutineScope,
) : AudioTokenProvider {
    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0L

    private val refreshMutex = Mutex()

    init {
        // Initial load
        scope.launch {
            refreshToken()
        }

        // Proactive refresh: check every 5 minutes, refresh if expiring within 10 minutes
        scope.launch {
            while (isActive) {
                delay(5.minutes)
                val now = Clock.System.now().toEpochMilliseconds()
                val expiresIn = tokenExpiresAt - now

                // Refresh if expiring within 10 minutes
                if (expiresIn < 10.minutes.inWholeMilliseconds) {
                    logger.debug { "Proactive token refresh: expires in ${expiresIn / 1000}s" }
                    refreshToken()
                }
            }
        }
    }

    /**
     * Non-blocking read for networking code.
     * Returns null if no token available.
     */
    override fun getToken(): String? = cachedToken

    /**
     * Called before starting playback to ensure fresh token.
     */
    override suspend fun prepareForPlayback() {
        refreshToken()
    }

    /**
     * Called on 401 response from network layer.
     * Triggers async refresh, returns immediately.
     */
    fun onUnauthorized() {
        scope.launch {
            logger.warn { "Token unauthorized, refreshing..." }
            refreshToken()
        }
    }

    private suspend fun refreshToken() {
        // Prevent concurrent refreshes
        refreshMutex.withLock {
            try {
                // Always try API refresh first to get a guaranteed-fresh token
                val refreshTokenValue = authSession.getRefreshToken()
                if (refreshTokenValue != null) {
                    try {
                        val response = authApi.refresh(refreshTokenValue)

                        // Save new tokens
                        val sessionId = authSession.getSessionId() ?: ""
                        val userId = authSession.getUserId() ?: ""
                        authSession.saveAuthTokens(
                            access = AccessToken(response.accessToken),
                            refresh = RefreshToken(response.refreshToken),
                            sessionId = sessionId,
                            userId = userId,
                        )

                        cachedToken = response.accessToken
                        tokenExpiresAt = Clock.System.now().toEpochMilliseconds() + 50.minutes.inWholeMilliseconds
                        logger.info { "Token refreshed successfully" }
                        return
                    } catch (e: Exception) {
                        logger.warn(e) { "API token refresh failed, falling back to stored token" }
                    }
                }

                // Fallback: use stored token (may be stale but better than nothing)
                val currentToken = authSession.getAccessToken()
                if (currentToken != null) {
                    cachedToken = currentToken.value
                    tokenExpiresAt = Clock.System.now().toEpochMilliseconds() + 50.minutes.inWholeMilliseconds
                    logger.debug { "Token loaded from storage (fallback)" }
                    return
                }

                logger.warn { "No token available" }
                cachedToken = null
            } catch (e: Exception) {
                logger.error(e) { "Error during token refresh" }
            }
        }
    }
}
