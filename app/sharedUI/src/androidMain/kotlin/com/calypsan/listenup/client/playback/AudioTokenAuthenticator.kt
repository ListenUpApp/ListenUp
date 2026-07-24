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
 * Coalescing: concurrent 401s on different Media3 segments funnel into
 * [CachedAudioTokenProvider.refreshToken], which serialises through its
 * internal `refreshMutex`. The first 401 does the network refresh; later
 * 401s observe the freshly-stored token via [CachedAudioTokenProvider.getToken]
 * without re-hitting the server.
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
