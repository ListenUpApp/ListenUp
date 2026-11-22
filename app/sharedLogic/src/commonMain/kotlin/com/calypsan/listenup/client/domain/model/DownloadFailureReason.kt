package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.error.TransportError

/**
 * Why a download gave up, in the words a person can act on. Read from the error code the failed row recorded, so a
 * surface says "not enough space on this device" rather than a generic failure.
 */
enum class DownloadFailureReason {
    /** The device ran out of room. Freeing space and retrying is the way forward. */
    NOT_ENOUGH_SPACE,

    /** The server failed or went quiet partway through. Retrying later may work. */
    SERVER_STOPPED_ANSWERING,

    /** Anything else: the download did not finish and no more specific reason is known. */
    UNKNOWN,
    ;

    /** How a failed row's recorded error code reads. */
    companion object {
        /** The reason for a row whose last recorded error code is [code]; null or unknown codes read [UNKNOWN]. */
        fun fromErrorCode(code: String?): DownloadFailureReason =
            when (code) {
                DownloadError.InsufficientStorage().code -> NOT_ENOUGH_SPACE
                in SERVER_FAILURE_CODES -> SERVER_STOPPED_ANSWERING
                else -> UNKNOWN
            }
    }
}

/** The error codes of a server that failed or went quiet partway through a transfer. */
private val SERVER_FAILURE_CODES =
    setOf(
        TransportError.Server5xx(statusCode = 500).code,
        TransportError.OutcomeUnknown().code,
        TransportError.Timeout().code,
    )
