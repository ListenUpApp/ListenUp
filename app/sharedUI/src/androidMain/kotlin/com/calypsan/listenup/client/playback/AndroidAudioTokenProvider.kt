package com.calypsan.listenup.client.playback

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Android wrapper around [CachedAudioTokenProvider]. Adds the only thing
 * Android needs that the shared core doesn't: an OkHttp [Interceptor] that
 * stamps `Authorization: Bearer …` on every Media3 stream request. The 401
 * refresh-and-retry path lives in [AudioTokenAuthenticator], installed
 * separately on the OkHttp client.
 *
 * Delegates the [AudioTokenProvider] surface to the shared core.
 */
class AndroidAudioTokenProvider(
    private val core: CachedAudioTokenProvider,
) : AudioTokenProvider by core {
    /**
     * Creates an OkHttp interceptor that stamps the bearer token onto every
     * request when one is cached.
     */
    fun createInterceptor(): Interceptor = AuthInterceptor(core)

    /**
     * Creates the OkHttp [okhttp3.Authenticator] that refreshes the bearer
     * token on 401 and re-issues the failed request. Pair this with
     * [createInterceptor] on the same `OkHttpClient` builder.
     */
    fun createAuthenticator(): AudioTokenAuthenticator = AudioTokenAuthenticator(core)

    /**
     * Forwarded to [CachedAudioTokenProvider.onUnauthorized] — exposed on the
     * Android wrapper so callers like [PlaybackErrorHandler] can trigger the
     * cache invalidation without depending on the shared concrete type.
     * `by core` only forwards the [AudioTokenProvider] interface; this method
     * sits outside it.
     */
    fun onUnauthorized() = core.onUnauthorized()
}

/**
 * OkHttp interceptor that stamps the bearer token onto every request.
 * 401 handling is delegated to [AudioTokenAuthenticator].
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
        return chain.proceed(request)
    }
}
