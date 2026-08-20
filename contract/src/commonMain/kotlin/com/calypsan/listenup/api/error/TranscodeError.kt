package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain errors for on-demand transcoding.
 *
 * Transcoding is what lets a client play a codec it cannot decode, so these are the errors a
 * listener meets when that rescue path is unavailable rather than when playback itself fails.
 * None of them mention codecs or FFmpeg: the listener chose a book, not an encoder.
 */
@Serializable
sealed interface TranscodeError : AppError {
    /**
     * Every encoder slot is in use.
     *
     * The only retryable member of this family — the admission gate refuses immediately rather than
     * queueing, so re-firing the same request once a slot frees is exactly the right recovery.
     */
    @Serializable
    @SerialName("TranscodeError.TranscoderBusy")
    data class TranscoderBusy(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TranscodeError {
        override val message: String = "The server is busy converting other books. Try again in a moment."
        override val code: String = "TRANSCODE_BUSY"
        override val isRetryable: Boolean = true
    }

    /**
     * No working encoder exists on the server, so a file this client cannot decode cannot be
     * rescued. Retrying changes nothing — an operator has to install FFmpeg or enable transcoding.
     */
    @Serializable
    @SerialName("TranscodeError.TranscoderUnavailable")
    data class TranscoderUnavailable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TranscodeError {
        override val message: String = "This server cannot convert audio for your device."
        override val code: String = "TRANSCODE_UNAVAILABLE"
        override val isRetryable: Boolean = false
    }

    /**
     * The encoder ran and failed — a corrupt or unreadable source, most often.
     *
     * [debugInfo] carries the tail of FFmpeg's stderr for the operator's logs. It is deliberately
     * absent from [message]: the listener gets a sentence, never a transcoder diagnostic.
     */
    @Serializable
    @SerialName("TranscodeError.TranscodeFailed")
    data class TranscodeFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TranscodeError {
        override val message: String = "This book could not be converted for playback."
        override val code: String = "TRANSCODE_FAILED"
        override val isRetryable: Boolean = false
    }
}
