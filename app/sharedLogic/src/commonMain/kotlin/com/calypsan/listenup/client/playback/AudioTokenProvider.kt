package com.calypsan.listenup.client.playback

/**
 * Interface for audio playback authentication.
 *
 * Provides tokens for authenticated audio streaming.
 * Platform implementations handle HTTP interceptor integration.
 *
 * Android: Implemented by full AudioTokenProvider with OkHttp interceptor
 * iOS: Will integrate with URLSession authentication
 */
interface AudioTokenProvider {
    /**
     * Ensure a fresh token is available before playback.
     * Call before starting audio streams.
     */
    suspend fun prepareForPlayback()

    /**
     * Get current token, or null if not authenticated.
     * Non-blocking read for use in HTTP interceptors.
     */
    fun getToken(): String?
}
