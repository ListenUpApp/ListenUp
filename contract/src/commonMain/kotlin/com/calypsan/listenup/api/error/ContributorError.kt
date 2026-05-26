package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from contributor operations exposed through
 * [com.calypsan.listenup.api.ContributorService].
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` for all subtypes — contributor failures require user
 * action (correct the input, verify the contributor exists, etc.).
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound] → 404
 * - [InvalidInput] → 400
 */
@Serializable
sealed interface ContributorError : AppError {
    /**
     * No contributor with the given id exists, or the contributor has been soft-deleted.
     * Raised by mutations that address a specific contributor when it cannot be found.
     */
    @Serializable
    @SerialName("ContributorError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ContributorError {
        override val message: String = "This contributor no longer exists."
        override val code: String = "CONTRIBUTOR_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * A supplied field value failed validation — empty name, invalid role, or any
     * other constraint enforced at the API boundary.
     * Raised by mutations that accept user-supplied contributor metadata.
     */
    @Serializable
    @SerialName("ContributorError.InvalidInput")
    data class InvalidInput(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ContributorError {
        override val message: String = "Some of the changes couldn't be saved."
        override val code: String = "CONTRIBUTOR_INVALID_INPUT"
        override val isRetryable: Boolean = false
    }
}
