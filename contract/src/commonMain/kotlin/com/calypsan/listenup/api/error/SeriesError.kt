package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from series operations exposed through
 * [com.calypsan.listenup.api.SeriesService].
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` for all subtypes — series failures require user
 * action (correct the input, verify the series exists, etc.).
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound] → 404
 * - [InvalidInput] → 400
 * - [MergeSelfTarget] → 400
 */
@Serializable
sealed interface SeriesError : AppError {
    /**
     * No series with the given id exists, or the series has been soft-deleted.
     * Raised by mutations that address a specific series when it cannot be found.
     */
    @Serializable
    @SerialName("SeriesError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SeriesError {
        override val message: String = "This series no longer exists."
        override val code: String = "SERIES_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * A supplied field value failed validation — empty name, or any other
     * constraint enforced at the API boundary.
     * Raised by mutations that accept user-supplied series metadata.
     */
    @Serializable
    @SerialName("SeriesError.InvalidInput")
    data class InvalidInput(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SeriesError {
        override val message: String = "Some of the changes couldn't be saved."
        override val code: String = "SERIES_INVALID_INPUT"
        override val isRetryable: Boolean = false
    }

    /**
     * Returned by [com.calypsan.listenup.api.SeriesService.mergeSeries] when called
     * with `source == target`. A series can't be merged with itself.
     */
    @Serializable
    @SerialName("SeriesError.MergeSelfTarget")
    data class MergeSelfTarget(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SeriesError {
        override val message: String = "A series can't be merged with itself."
        override val code: String = "SERIES_MERGE_SELF_TARGET"
        override val isRetryable: Boolean = false
    }
}
