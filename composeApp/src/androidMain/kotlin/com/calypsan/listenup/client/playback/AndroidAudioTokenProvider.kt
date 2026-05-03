package com.calypsan.listenup.client.playback

import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.Interceptor
import okhttp3.Response

private val logger = KotlinLogging.logger {}

/**
 * Android wrapper around [CachedAudioTokenProvider]. Adds the only thing
 * Android needs that the shared core doesn't: an OkHttp [Interceptor] that
 * stamps `Authorization: Bearer …` on every Media3 stream request and
 * triggers refresh on 401.
 *
 * Delegates the [AudioTokenProvider] surface to the shared core.
 */
class AndroidAudioTokenProvider(
    private val core: CachedAudioTokenProvider,
) : AudioTokenProvider by core {
    /**
     * Creates an OkHttp interceptor that stamps the bearer token onto every
     * request and triggers refresh + one retry on 401.
     */
    fun createInterceptor(): Interceptor = AuthInterceptor(core)
}

/**
 * OkHttp interceptor that stamps the bearer token onto every request.
 * On 401, kicks off an async refresh and retries once with whatever token
 * landed in the cache.
 *
 * Note: the `Thread.sleep(500)` between trigger and retry is a hack — see
 * Phase 1 deferrals (`phase_1_auth_deferrals.md`) for the cleanup plan.
 */
private class AuthInterceptor(
    private val tokenProvider: CachedAudioTokenProvider,
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

        if (response.code == HTTP_UNAUTHORIZED && token != null) {
            logger.debug { "Got 401, triggering token refresh" }
            tokenProvider.onUnauthorized()
            response.close()

            @Suppress("MagicNumber")
            Thread.sleep(REFRESH_WAIT_MS)

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
        private const val HTTP_UNAUTHORIZED = 401
        private const val REFRESH_WAIT_MS = 500L
    }
}
