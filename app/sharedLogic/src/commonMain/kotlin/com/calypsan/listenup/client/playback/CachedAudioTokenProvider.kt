@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.DEFAULT_RPC_TIMEOUT
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/** A token with more than this left is usable as-is — by the cache fast path and by a stored-token adopt. */
private val PREPARE_PLAYBACK_FAST_PATH = 2.minutes

/**
 * Shared core for platform audio-token providers. Caches the current [AccessToken] and its expiry,
 * and makes it usable **only when a consumer asks** ([prepareForPlayback]): first by adopting a
 * still-fresh token already in [AuthSession] (another refresh authority may have rotated it), and
 * only then by rotating through [AuthRepository.refreshAccessToken]. When the network refresh fails
 * it falls back to whatever is stored, so Media3/AVFoundation can still play cached/local content
 * while the user reconnects.
 *
 * **It never refreshes on its own.** It used to rotate the session on every construction (the
 * stored token is always near expiry after a closure) and every 5 minutes after that, foreground or
 * not. The provider is built on every process start — SyncWorker, FCM and Android Auto wakes
 * included — so rotations landed exactly where Android freezes or kills the process. A reply lost
 * there left the old refresh token on disk; the next refresh presented it after the server's
 * 30-minute reuse grace and the server revoked the whole session family: the "Sign in to sync"
 * banner after a night with the app closed. Nothing on the audio path needs that rotation — streams
 * and downloads authenticate by URL signature (a 12-hour one) — so on-demand is the whole contract:
 * a play start (from the app, Android Auto, or a media button), an iOS cover load, a download, or a
 * 401 ([refreshToken]). An idle process performs no rotations at all.
 *
 * Cross-platform by design: iOS and web bind this directly; Android wraps it with the OkHttp
 * interceptor glue and exposes the same `AudioTokenProvider` interface via delegation.
 *
 * Threading: the cached fields are `@Volatile` so [getToken] never blocks an OkHttp dispatcher /
 * URLSession thread. The refresh path serialises through a [Mutex]. Serialising is not the same as
 * coalescing, and the difference is load-bearing: [prepareForPlayback] re-checks the cache *after*
 * taking the lock and adopts whatever an in-flight refresh produced, while [refreshToken] always
 * rotates. Without that re-check every waiter ran its own full round-trip, which on a half-open
 * socket is the 15s RPC bound each — the resume-latency bug.
 *
 * Concurrency note: this is a *separate* refresh authority from the Ktor bearer plugin and the RPC
 * 401-heal. All of them rotate through the single-flight [AuthRepository.refreshAccessToken], which
 * persists to [AuthSession]; [prepareForPlayback] reads that store before rotating, so a rotation
 * one of them just made is adopted rather than repeated.
 *
 * The [clock] defaults to [Clock.System]; tests inject a virtual clock.
 */
class CachedAudioTokenProvider(
    private val authSession: AuthSession,
    private val authRepository: AuthRepository,
    private val clock: Clock = Clock.System,
) : AudioTokenProvider {
    @Volatile
    private var cachedToken: AccessToken? = null

    @Volatile
    private var tokenExpiresAt: Long = 0L

    private val refreshMutex = Mutex()

    override fun getToken(): String? = cachedToken?.value

    override suspend fun prepareForPlayback() {
        if (hasUsableToken()) return
        val refreshed =
            withTimeoutOrNull(PREPARE_PLAYBACK_REFRESH_BOUND) {
                refreshMutex.withLock {
                    // Re-check under the lock. A refresh that landed while we waited has already
                    // produced a usable token — or installed the stored-token fallback — so firing
                    // our own would only queue a second full round-trip behind the first. On a
                    // half-open socket each one costs the entire 15s RPC bound, which is how a
                    // resume after a long idle spent 24s here before playing a book that was
                    // already downloaded.
                    if (hasUsableToken() || adoptUsableStoredToken()) return@withLock
                    performRefresh()
                }
            }
        if (refreshed == null) {
            // Budget exceeded — either this call was still queued behind another in-flight refresh's
            // mutex hold, or its own refresh RPC, when the playback-start budget ran out. Reach for
            // the same stored-token fallback a failed refresh would install anyway (fallbackToStored,
            // below); the only difference is not waiting up to 15s to find out. This write races
            // whichever refresh is still in flight on the @Volatile cache fields below — a race this
            // class already tolerates (see the class KDoc's Concurrency note): last write wins, and
            // the in-flight refresh's own eventual write supersedes this one when it lands.
            logger.warn {
                "Token refresh exceeded the $PREPARE_PLAYBACK_REFRESH_BOUND playback-start budget; falling back to stored"
            }
            fallbackToStored()
        }
    }

    /**
     * Whether the cache holds a token with enough life left to start playback on — the shared
     * predicate behind [prepareForPlayback]'s fast path and its re-check under the lock.
     */
    private fun hasUsableToken(): Boolean =
        cachedToken != null &&
            tokenExpiresAt - now() > PREPARE_PLAYBACK_FAST_PATH.inWholeMilliseconds

    /**
     * Force a token rotation, serialising on [refreshMutex]. Callers that merely need a *usable*
     * token should use [prepareForPlayback], which coalesces onto an in-flight refresh instead;
     * this entry point is for callers that know the cached token is bad (a 401), where re-checking
     * the cache would defeat the point.
     *
     * The mutex **serialises — it does not dedupe.** Two forced rotations queue, and each performs
     * its own upstream refresh; single-flight dedup of the rotation RPC itself lives one layer down,
     * in [AuthRepository.refreshAccessToken].
     *
     * Public because it's the synchronous seam for OkHttp's [okhttp3.Authenticator] contract — the
     * Android Authenticator wraps this in `runBlocking` to satisfy OkHttp's blocking-thread
     * expectation while still routing through the shared refresh path. On success, [getToken]
     * returns the new token; on failure, [fallbackToStored] surfaces whatever's in [AuthSession].
     */
    suspend fun refreshToken() {
        refreshMutex.withLock { performRefresh() }
    }

    /**
     * Rotate the token and cache it, or fall back to stored. Caller holds [refreshMutex].
     *
     * The upstream refresh is bounded to [FORCED_REFRESH_BOUND] via [withTimeoutOrNull], wrapped
     * DIRECTLY around [authRepository]'s call — safe to do only because
     * [com.calypsan.listenup.client.data.repository.AuthRepositoryImpl.refreshAccessToken] already
     * runs its own rotation on a scope independent of whichever caller invokes it. Abandoning this
     * `withTimeoutOrNull`'s wait therefore only stops WAITING; it never cancels the rotation itself,
     * which keeps running for whoever else is waiting, or for the next call to find already done.
     * See that method's KDoc for the full reasoning — this bound would be actively unsafe wrapped
     * around a refresh that ran on the calling coroutine instead. Unbounded, this call left
     * [refreshToken] able to hang forever waiting on a dead/half-open socket — and [refreshToken] is
     * called from `AudioTokenAuthenticator` via `runBlocking` on a SHARED OkHttp dispatcher thread,
     * so that hang blocked the whole request pool, not just this one call. This bound also covers
     * [prepareForPlayback]'s call into [performRefresh] — that path has no tighter budget of its own
     * in this codebase yet, so it inherits this one too.
     */
    private suspend fun performRefresh() {
        when (val result = withTimeoutOrNull(FORCED_REFRESH_BOUND) { authRepository.refreshAccessToken() }) {
            is AppResult.Success -> {
                // Persistence already happened inside the single-flight refresh (C1); here we only
                // update this provider's in-memory cache so getToken() serves the fresh token.
                val session = result.data
                cachedToken = session.accessToken
                tokenExpiresAt = session.accessTokenExpiresAt
                logger.info { "Token refreshed successfully" }
            }

            is AppResult.Failure -> {
                logger.warn { "Token refresh failed (${result.error}), falling back to stored" }
                fallbackToStored()
            }

            null -> {
                logger.warn { "Token refresh exceeded the $FORCED_REFRESH_BOUND bound; falling back to stored" }
                fallbackToStored()
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

    private fun now(): Long = clock.now().toEpochMilliseconds()

    /**
     * Adopts the access token already in [AuthSession] when it is a decodable JWT still usable by
     * [hasUsableToken]'s margin — no network, no rotation. The token may be one this provider cached
     * long ago, or one another refresh authority (the bearer plugin, the RPC 401-heal) has rotated
     * since. @return true when it adopted one; false when a real refresh is warranted.
     */
    private suspend fun adoptUsableStoredToken(): Boolean {
        val stored = authSession.getAccessToken() ?: return false
        val expiry = jwtExpiryMillis(stored.value) ?: return false
        if (expiry - now() <= PREPARE_PLAYBACK_FAST_PATH.inWholeMilliseconds) return false
        cachedToken = stored
        tokenExpiresAt = expiry
        return true
    }

    /**
     * Best-effort read of a JWT's `exp` claim (seconds → epoch millis). Returns null for anything not
     * a well-formed JWT with a numeric `exp` — the caller then falls back to a refresh, so a malformed
     * or opaque token can never be trusted as fresh.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun jwtExpiryMillis(token: String): Long? =
        runCatching {
            val payload = token.split('.').getOrNull(1) ?: return null
            val decoded =
                Base64.UrlSafe
                    .withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                    .decode(payload)
                    .decodeToString()
            Json
                .parseToJsonElement(decoded)
                .jsonObject["exp"]
                ?.jsonPrimitive
                ?.longOrNull
                ?.let { it * MILLIS_PER_SECOND }
        }.getOrNull()

    companion object {
        private val STORED_TOKEN_GRACE = 50.minutes
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * Latency budget for [prepareForPlayback]'s wait — bounds BOTH queuing behind another
         * in-flight refresh (the coalescing re-check above) and, if this call ends up performing
         * the refresh itself, the RPC round-trip. Mirrors
         * [com.calypsan.listenup.client.data.repository.PlaybackPrepareRepositoryImpl]'s
         * `RESUME_POSITION_FETCH_BOUND`: a healthy refresh answers in well under this, so the
         * budget only bites when the connection is degraded — and 800ms of visible delay beats the
         * 15s RPC-timeout wait this class produced pre-fix, when a tap coalesced onto a refresh that
         * started before the tap and inherited whatever remained of its bound (the 8.08s device
         * capture on 2026-08-13).
         */
        private val PREPARE_PLAYBACK_REFRESH_BOUND = 800.milliseconds

        /**
         * Latency budget for [performRefresh]'s upstream refresh call — see its KDoc. One RPC
         * attempt's worth of waiting ([DEFAULT_RPC_TIMEOUT]), never the doubled ~30s a pre-delivery
         * transport retry can add on top: enough for a legitimately slow single attempt to
         * complete, but short enough that a dead/half-open socket can't block the shared OkHttp
         * dispatcher thread [refreshToken] runs on indefinitely.
         *
         * Distinct from [PREPARE_PLAYBACK_REFRESH_BOUND], which is far tighter because a listener is
         * waiting on it; this one is the outer net for the forced rotation, where nobody is watching
         * a play button but an OkHttp worker thread is held.
         */
        private val FORCED_REFRESH_BOUND = DEFAULT_RPC_TIMEOUT
    }
}
