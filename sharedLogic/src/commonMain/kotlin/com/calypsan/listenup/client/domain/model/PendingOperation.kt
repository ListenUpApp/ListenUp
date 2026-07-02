package com.calypsan.listenup.client.domain.model

/**
 * Domain model representing a pending sync operation.
 *
 * Used by the UI layer to display sync status and failed operations.
 * Contains only the information needed for UI display, hiding internal
 * sync implementation details.
 *
 * @property id Unique operation identifier
 * @property operationType Type of operation for description generation
 * @property entityId Optional entity ID (used for description)
 * @property status Current status (pending, in-progress, failed)
 * @property lastError Error message if the operation failed
 */
data class PendingOperation(
    val id: String,
    val operationType: PendingOperationType,
    val entityId: String?,
    val status: PendingOperationStatus,
    val lastError: String?,
)

/**
 * Operation types visible to the UI layer, keyed off the outbox row's
 * `domainName`. [OTHER] is the forward-compat fallback for a domain name
 * this build doesn't recognize.
 */
enum class PendingOperationType {
    BOOK_UPDATE,
    SERIES_UPDATE,
    CONTRIBUTOR_UPDATE,
    PROFILE_UPDATE,
    USER_PREFERENCES,
    PLAYBACK_POSITION,
    LISTENING_EVENT,
    OTHER,
}

/**
 * Operation status visible to the UI layer.
 */
enum class PendingOperationStatus {
    PENDING,
    IN_PROGRESS,
    FAILED,
}
