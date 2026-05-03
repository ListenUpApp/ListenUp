@file:OptIn(ExperimentalTime::class)
@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.data.remote.AuthApiContract
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * Thread-safe token provider for OkHttp interceptors.
 *
 * Design:
 * - Token is cached in a volatile field (non-blocking reads)
 * - Refresh happens proactively on a schedule AND reactively on 401
 * - Uses the existing auth infrastructure for actual refresh
 *
 * This solves the problem of needing authentication in OkHttp interceptors
 * (which are synchronous) without blocking on coroutine operations.
 *
 * Implements [com.calypsan.listenup.client.playback.AudioTokenProvider] interface
 * for use by shared code (PlaybackManager).
 */
class AndroidAudioTokenProvider(
    private val authSession: AuthSession,
    private val authApi: AuthApiContract,
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
     * Non-blocking read for interceptor.
     * Returns null if no token available.
     */
    override fun getToken(): String? = cachedToken

    /**
     * Called before starting playback to ensure fresh token.
     *
     * Fast-path: if a cached token exists and is more than 2 minutes from
     * expiry, return immediately without acquiring the refresh mutex.
     * This prevents blocking on a slow proactive refresh in progress.
     */
    override suspend fun prepareForPlayback() {
        val token = cachedToken
        val expiresAt = tokenExpiresAt
        if (token != null && expiresAt - Clock.System.now().toEpochMilliseconds() > 2.minutes.inWholeMilliseconds) {
            return
        }
        refreshToken()
    }

    /**
     * Called by interceptor on 401 response.
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
                // Try to use existing token first
                val currentToken = authSession.getAccessToken()
                if (currentToken != null) {
                    cachedToken = currentToken.value
                    // Estimate expiry (PASETO tokens are typically 15 min - 1 hour)
                    // Use 50 minutes as safe buffer
                    tokenExpiresAt = Clock.System.now().toEpochMilliseconds() + 50.minutes.inWholeMilliseconds
                    logger.debug { "Token loaded from storage" }
                    return
                }

                // No token in storage - try refresh
                val refreshToken = authSession.getRefreshToken()
                if (refreshToken == null) {
                    logger.warn { "No refresh token available" }
                    cachedToken = null
                    return
                }

                try {
                    val response = authApi.refresh(refreshToken)

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
                } catch (e: Exception) {
                    // Refresh failed - user needs to re-authenticate
                    logger.error(e) { "Token refresh failed" }
                    cachedToken = null
                }
            } catch (e: Exception) {
                logger.error(e) { "Error during token refresh" }
            }
        }
    }

    /**
     * Creates an OkHttp interceptor that adds Bearer token to requests.
     *
     * The interceptor:
     * - Adds Authorization header if token is available
     * - On 401 response, triggers refresh and retries once
     */
    fun createInterceptor(): Interceptor = AuthInterceptor(this)
}

/**
 * OkHttp interceptor that adds Bearer token to all requests.
 * Handles 401 responses by triggering token refresh and retrying.
 */
private class AuthInterceptor(
    private val tokenProvider: AndroidAudioTokenProvider,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider.getToken()

        val request =
            if (token != null) {
                chain
                    .request()
                    .newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }

        val response = chain.proceed(request)

        // Trigger refresh on 401, retry once
        if (response.code == 401 && token != null) {
            logger.debug { "Got 401, triggering token refresh" }
            tokenProvider.onUnauthorized()
            response.close()

            // Wait briefly for refresh, then retry
            Thread.sleep(500)

            val newToken = tokenProvider.getToken()
            if (newToken != null && newToken != token) {
                logger.debug { "Retrying with new token" }
                val retryRequest =
                    chain
                        .request()
                        .newBuilder()
                        .addHeader("Authorization", "Bearer $newToken")
                        .build()
                return chain.proceed(retryRequest)
            }
        }

        return response
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
