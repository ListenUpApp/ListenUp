package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from [com.calypsan.listenup.api.WorldEventService]. A member lacking the Story World
 * contribute permission gets [AuthError.PermissionDenied]; an event whose home or anchor the caller can't see
 * is [NotFound], so its existence never leaks. Input-shape problems are [ValidationError]s.
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`): [NotFound], [HistoryNotFound] → 404;
 * [InvalidAnchor], [EntityNotInWorld], [WrongEntityKind] → 400.
 */
@Serializable
sealed interface WorldEventError : AppError {
    /** No event with that id exists, it was deleted, or the caller can't see its home or anchor. */
    @Serializable
    @SerialName("WorldEventError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : WorldEventError {
        override val message: String = "This event is no longer in the story world."
        override val code: String = "WORLD_EVENT_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /** No history entry with that id exists for any event the caller can see. */
    @Serializable
    @SerialName("WorldEventError.HistoryNotFound")
    data class HistoryNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : WorldEventError {
        override val message: String = "That change can no longer be found."
        override val code: String = "WORLD_EVENT_HISTORY_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /** The anchor book isn't a live book of the event's world (or isn't one the caller can see). */
    @Serializable
    @SerialName("WorldEventError.InvalidAnchor")
    data class InvalidAnchor(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : WorldEventError {
        override val message: String = "That moment isn't in one of this story world's books."
        override val code: String = "WORLD_EVENT_INVALID_ANCHOR"
        override val isRetryable: Boolean = false
    }

    /** The subject or object isn't an entity of the event's world (or has been deleted). */
    @Serializable
    @SerialName("WorldEventError.EntityNotInWorld")
    data class EntityNotInWorld(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : WorldEventError {
        override val message: String = "That entry isn't part of this story world."
        override val code: String = "WORLD_EVENT_ENTITY_NOT_IN_WORLD"
        override val isRetryable: Boolean = false
    }

    /** The subject or object is a kind the event's type doesn't allow (a JOINS whose object isn't a group). */
    @Serializable
    @SerialName("WorldEventError.WrongEntityKind")
    data class WrongEntityKind(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : WorldEventError {
        override val message: String = "That kind of entry can't take that part in this event."
        override val code: String = "WORLD_EVENT_WRONG_ENTITY_KIND"
        override val isRetryable: Boolean = false
    }
}
