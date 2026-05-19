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
 * Operation types visible to the UI layer.
 *
 * Mirrors the data layer OperationType but lives in the domain layer
 * to avoid entity leakage into the presentation layer.
 */
enum class PendingOperationType {
    BOOK_UPDATE,
    CONTRIBUTOR_UPDATE,
    SERIES_UPDATE,
    SET_BOOK_CONTRIBUTORS,
    SET_BOOK_SERIES,
    MERGE_CONTRIBUTOR,
    UNMERGE_CONTRIBUTOR,
    LISTENING_EVENT,
    PLAYBACK_POSITION,
    USER_PREFERENCES,
    PROFILE_UPDATE,
    PROFILE_AVATAR,
    MARK_COMPLETE,
    DISCARD_PROGRESS,
    RESTART_BOOK,
    END_PLAYBACK_SESSION,
    CREATE_SHELF,
    UPDATE_SHELF,
    DELETE_SHELF,
    ADD_BOOKS_TO_SHELF,
    REMOVE_BOOK_FROM_SHELF,
}

/**
 * Operation status visible to the UI layer.
 */
enum class PendingOperationStatus {
    PENDING,
    IN_PROGRESS,
    FAILED,
}
