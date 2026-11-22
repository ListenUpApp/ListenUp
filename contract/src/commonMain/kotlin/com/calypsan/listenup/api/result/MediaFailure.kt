package com.calypsan.listenup.api.result

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.UnknownError
import kotlinx.serialization.builtins.serializer

/**
 * What a failed audio request means for playback: try again, or stop and say so.
 *
 * Players fetch audio outside the RPC channel — ExoPlayer's HTTP stack, hls.js — so the server's
 * typed error reaches them only as a status and a body. Every client decides from both, the same
 * way, here: retrying a segment the server has said it can never produce is a loop, not resilience.
 */
sealed interface MediaFailure {
    /** Re-requesting the same audio can succeed: the server is busy, or a proxy in front of it failed. */
    data object Transient : MediaFailure

    /**
     * Re-requesting cannot help. [error] is what the listener is told: [PlaybackError.CouldNotStart],
     * carrying the server's correlation id so the card's reference joins the server's log line.
     */
    data class Terminal(
        val error: AppError,
    ) : MediaFailure

    /** Decides a failure from its HTTP [status] and the reply [body], when the transport could read one. */
    companion object {
        private val RETRYABLE_WITHOUT_EXPLANATION = setOf(502, 503, 504)

        /**
         * The server's typed error decides when it sent one (`AppError.isRetryable`). Without one, a
         * gateway or overload status (502, 503, 504) is retried and any other status is not.
         */
        fun of(
            status: Int,
            body: String?,
        ): MediaFailure {
            val typed = body?.let(::decodeServerError)
            return when {
                typed != null && typed.isRetryable -> Transient
                typed != null -> Terminal(couldNotPlay(typed.correlationId, "server ${typed.code}: ${typed.debugInfo.orEmpty()}"))
                status in RETRYABLE_WITHOUT_EXPLANATION -> Transient
                else -> Terminal(couldNotPlay(correlationId = null, debugInfo = "HTTP $status with no typed error"))
            }
        }

        private fun couldNotPlay(
            correlationId: String?,
            debugInfo: String,
        ): AppError = PlaybackError.CouldNotStart(correlationId = correlationId, debugInfo = debugInfo)

        /** The error in a server's `AppResult.Failure` reply, or null when the body is not one. */
        private fun decodeServerError(body: String): AppError? =
            try {
                (contractJson.decodeFromString(AppResult.serializer(Unit.serializer()), body) as? AppResult.Failure)
                    ?.error
                    ?.takeUnless { it is UnknownError }
            } catch (_: IllegalArgumentException) {
                // SerializationException is an IllegalArgumentException: an HTML page from a proxy,
                // or any reply that is not a typed failure. The status decides instead.
                null
            }
    }
}
