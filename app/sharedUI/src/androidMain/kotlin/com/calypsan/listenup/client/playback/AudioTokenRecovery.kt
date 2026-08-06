package com.calypsan.listenup.client.playback

/**
 * The audio-token operations playback recovery needs when a stream returns 401.
 *
 * A seam, not a convenience. Recovery has to distinguish "the refresh produced a genuinely new
 * token" from "the refresh failed and re-cached the very token that just 401'd" — and it has to
 * *await* the refresh rather than sleep and hope. Neither is expressible against
 * [AudioTokenProvider]: its [AudioTokenProvider.prepareForPlayback] short-circuits on a token that
 * still looks fresh locally, which is precisely the case a 401 disproves.
 *
 * Keeping this narrow is the point — [PlaybackErrorHandler] holds only these two operations, so the
 * fire-and-forget invalidation that made recovery unobservable is not reachable from it.
 */
interface AudioTokenRecovery {
    /** The token currently cached, or `null` when there is none. Never performs I/O. */
    fun currentToken(): String?

    /**
     * Forces a refresh attempt, suspending until it has succeeded or failed.
     *
     * Failure is not signalled by an exception or a result: on a failed refresh the underlying
     * provider falls back to whatever is stored, so callers must compare [currentToken] before and
     * after to learn whether anything actually changed.
     */
    suspend fun refresh()
}
