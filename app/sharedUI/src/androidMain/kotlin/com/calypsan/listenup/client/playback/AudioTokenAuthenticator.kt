package com.calypsan.listenup.client.playback

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

private val logger = KotlinLogging.logger {}

/**
 * OkHttp [Authenticator] that refreshes the audio bearer token on 401 and
 * re-issues the failed request with the new token. Returns `null` when the
 * refresh produced no new token, which signals OkHttp to surface the 401
 * to the caller (Media3) — the standard "give up" path.
 *
 * Why `runBlocking`: OkHttp's [Authenticator] contract is synchronous — it
 * runs on the dispatcher's worker thread and expects a [Request] (or `null`)
 * to come back from a single call. Bridging that to our suspending refresh
 * with `runBlocking` is the canonical glue, and is the same pattern Ktor's
 * own bearer-auth plugin uses internally to bridge its suspending refresh
 * into OkHttp/CIO blocking call sites. The Authenticator's worker thread is
 * exactly the right place for this block — no UI thread is involved, and
 * OkHttp will not dispatch another request on the same call until we return.
 *
 * No coalescing: concurrent 401s on different Media3 segments funnel into
 * [CachedAudioTokenProvider.refreshToken], which only *serialises* through its internal
 * `refreshMutex` — it does not dedupe (see that class's own KDoc). Each queued 401 performs its
 * own full upstream round-trip in turn; a later 401 does NOT simply observe whatever the first
 * one stored. [CachedAudioTokenProvider.prepareForPlayback] is the one caller that coalesces onto
 * an in-flight refresh instead of firing its own; [refreshToken] deliberately does not, because a
 * 401 means the CACHED token is confirmed bad and callers need a rotation, not a re-check of a
 * cache that just failed them.
 */
class AudioTokenAuthenticator(
    private val tokenProvider: CachedAudioTokenProvider,
) : Authenticator {
    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        val previousToken = tokenProvider.getToken()
        logger.debug { "Got 401, refreshing audio token" }

        runBlocking { tokenProvider.refreshToken() }

        val newToken = tokenProvider.getToken()
        if (newToken == null || newToken == previousToken) {
            logger.warn { "Token refresh produced no new token, giving up" }
            return null
        }

        return response.request
            .newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }
}
