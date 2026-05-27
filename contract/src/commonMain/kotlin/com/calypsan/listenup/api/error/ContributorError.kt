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
 * - [MergeSelfTarget] → 400
 * - [AliasNotFound] → 404
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

    /**
     * Returned by [com.calypsan.listenup.api.ContributorService.mergeContributors] when
     * called with `source == target`. A contributor can't be merged with itself.
     */
    @Serializable
    @SerialName("ContributorError.MergeSelfTarget")
    data class MergeSelfTarget(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ContributorError {
        override val message: String = "A contributor can't be merged with itself."
        override val code: String = "CONTRIBUTOR_MERGE_SELF_TARGET"
        override val isRetryable: Boolean = false
    }

    /**
     * Returned by [com.calypsan.listenup.api.ContributorService.unmergeContributor] when
     * the supplied `aliasName` isn't present on the target contributor's `aliases` array.
     * The merge being undone may already have been undone, or the alias was never there.
     */
    @Serializable
    @SerialName("ContributorError.AliasNotFound")
    data class AliasNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ContributorError {
        override val message: String = "That alias isn't attached to this contributor."
        override val code: String = "CONTRIBUTOR_ALIAS_NOT_FOUND"
        override val isRetryable: Boolean = false
    }
}
