package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from [com.calypsan.listenup.api.EntityService]. A member lacking a Story World
 * permission gets [AuthError.PermissionDenied], like every other permission gate; an entity whose home
 * the caller can't see is [NotFound], so its existence never leaks.
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`): [NotFound], [HistoryNotFound] → 404;
 * [InvalidParent] → 400; [CycleDetected], [KindMismatchOnMerge] → 409.
 */
@Serializable
sealed interface EntityError : AppError {
    /** No entity with that id exists, it was deleted, or the caller can't see its home. */
    @Serializable
    @SerialName("EntityError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : EntityError {
        override val message: String = "This story world entry no longer exists."
        override val code: String = "ENTITY_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The chosen parent can't contain this entity: it's missing, of another kind, in another home, or
     * the kind doesn't nest (only places within places and groups within groups do).
     */
    @Serializable
    @SerialName("EntityError.InvalidParent")
    data class InvalidParent(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : EntityError {
        override val message: String = "That can't contain this entry."
        override val code: String = "ENTITY_INVALID_PARENT"
        override val isRetryable: Boolean = false
    }

    /** The chosen parent is the entity itself or one of its own descendants. */
    @Serializable
    @SerialName("EntityError.CycleDetected")
    data class CycleDetected(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : EntityError {
        override val message: String = "An entry can't be placed inside itself."
        override val code: String = "ENTITY_CYCLE"
        override val isRetryable: Boolean = false
    }

    /** Only entities of the same kind can be merged. */
    @Serializable
    @SerialName("EntityError.KindMismatchOnMerge")
    data class KindMismatchOnMerge(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : EntityError {
        override val message: String = "Only entries of the same kind can be merged."
        override val code: String = "ENTITY_MERGE_KIND_MISMATCH"
        override val isRetryable: Boolean = false
    }

    /** No history entry with that id exists for anything the caller can see. */
    @Serializable
    @SerialName("EntityError.HistoryNotFound")
    data class HistoryNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : EntityError {
        override val message: String = "That change can no longer be found."
        override val code: String = "ENTITY_HISTORY_NOT_FOUND"
        override val isRetryable: Boolean = false
    }
}
