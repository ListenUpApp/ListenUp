@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.AuthRepository
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

/** Refresh proactively when the cached token has less than this remaining. */
private val PROACTIVE_REFRESH_HORIZON = 10.minutes

/** Skip the refresh on `prepareForPlayback` if the cached token still has more than this. */
private val PREPARE_PLAYBACK_FAST_PATH = 2.minutes

/** Cadence of the background expiry-check loop. */
private val PROACTIVE_CHECK_CADENCE = 5.minutes

/**
 * Shared core for platform audio-token providers. Caches the current
 * [AccessToken] and the server-issued expiry timestamp, refreshes through
 * [AuthRepository.refreshAccessToken] when the cache is stale, and falls
 * back to whatever is sitting in [AuthSession] when the network refresh
 * fails — Media3/AVFoundation can still play cached/local content with
 * a stale-but-stored token while the user reconnects.
 *
 * Cross-platform by design: iOS/macOS bind this directly; Android wraps it
 * with the OkHttp interceptor glue and exposes the same `AudioTokenProvider`
 * interface via delegation.
 *
 * Threading: the cached fields are `@Volatile` so [getToken] never blocks an
 * OkHttp dispatcher / URLSession thread. The refresh path serialises through
 * a [Mutex] so concurrent triggers (init, proactive loop, on-401) coalesce.
 *
 * Concurrency note: this is a *separate* refresh authority from the Ktor
 * bearer plugin. Both write to [AuthSession.saveAuthTokens]. When two
 * refreshes interleave, last-write-wins on the stored tokens — both will
 * observe the most recent rotation on their next read. A unified refresh
 * authority is tracked as a Phase 1 deferral.
 */
class CachedAudioTokenProvider(
    private val authSession: AuthSession,
    private val authRepository: AuthRepository,
    private val scope: CoroutineScope,
) : AudioTokenProvider {
    @Volatile
    private var cachedToken: AccessToken? = null

    @Volatile
    private var tokenExpiresAt: Long = 0L

    private val refreshMutex = Mutex()

    init {
        scope.launch { refreshToken() }
        scope.launch {
            while (isActive) {
                delay(PROACTIVE_CHECK_CADENCE)
                val remaining = tokenExpiresAt - now()
                if (remaining < PROACTIVE_REFRESH_HORIZON.inWholeMilliseconds) {
                    logger.debug { "Proactive token refresh: expires in ${remaining / 1000}s" }
                    refreshToken()
                }
            }
        }
    }

    override fun getToken(): String? = cachedToken?.value

    override suspend fun prepareForPlayback() {
        if (cachedToken != null &&
            tokenExpiresAt - now() > PREPARE_PLAYBACK_FAST_PATH.inWholeMilliseconds
        ) {
            return
        }
        refreshToken()
    }

    /** Triggered by transport-level 401 handling; returns immediately. */
    fun onUnauthorized() {
        scope.launch {
            logger.warn { "Token unauthorized, refreshing..." }
            refreshToken()
        }
    }

    /**
     * Refreshes the cached token, blocking on the [refreshMutex] so concurrent
     * triggers coalesce. Public because it's the synchronous seam for OkHttp's
     * [okhttp3.Authenticator] contract — the Android Authenticator wraps this
     * in `runBlocking` to satisfy OkHttp's blocking-thread expectation while
     * still routing through the shared refresh path used by the proactive loop
     * and `prepareForPlayback`. On success, [getToken] returns the new token;
     * on failure, [fallbackToStored] surfaces whatever's in [AuthSession].
     */
    suspend fun refreshToken() {
        refreshMutex.withLock {
            when (val result = authRepository.refreshAccessToken()) {
                is AppResult.Success -> {
                    val session = result.data
                    authSession.saveAuthTokens(
                        access = session.accessToken,
                        refresh = session.refreshToken,
                        sessionId = session.sessionId.value,
                        userId = session.user.id.value,
                    )
                    cachedToken = session.accessToken
                    tokenExpiresAt = session.accessTokenExpiresAt
                    logger.info { "Token refreshed successfully" }
                }

                is AppResult.Failure -> {
                    logger.warn { "Token refresh failed (${result.error}), falling back to stored" }
                    fallbackToStored()
                }
            }
        }
    }

    /**
     * Fallback path when refresh fails: surface whatever is in [AuthSession]
     * so cached/local content still plays. Server-side expiry is unknown
     * here — assume the stored access token is at most 50 minutes from
     * being useful, matching the legacy heuristic. The next playback
     * attempt will trigger another refresh attempt.
     */
    private suspend fun fallbackToStored() {
        val stored = authSession.getAccessToken()
        if (stored != null) {
            cachedToken = stored
            tokenExpiresAt = now() + STORED_TOKEN_GRACE.inWholeMilliseconds
            logger.debug { "Token loaded from storage (fallback)" }
        } else {
            cachedToken = null
            tokenExpiresAt = 0L
            logger.warn { "No token available" }
        }
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        private val STORED_TOKEN_GRACE = 50.minutes
    }
}
