package com.calypsan.listenup.client.domain.model

/**
 * Domain events for admin-related real-time updates.
 *
 * These events are emitted from the SSE stream and consumed by
 * admin ViewModels. They use domain models (not data layer models)
 * to maintain Clean Architecture boundaries.
 */
sealed interface AdminEvent {
    /**
     * A new user registered and is pending approval.
     */
    data class UserPending(
        val user: AdminUserInfo,
    ) : AdminEvent

    /**
     * A pending user was approved and is now active.
     */
    data class UserApproved(
        val user: AdminUserInfo,
    ) : AdminEvent

    /**
     * A book was added to the inbox staging area.
     */
    data class InboxBookAdded(
        val bookId: String,
        val title: String,
    ) : AdminEvent

    /**
     * A book was released from the inbox to the library.
     */
    data class InboxBookReleased(
        val bookId: String,
    ) : AdminEvent
}

/**
 * Domain events for book-related real-time updates.
 *
 * These events are emitted from the SSE stream and consumed by
 * book detail ViewModels.
 */
sealed interface BookEvent {
    /**
     * A reading session was updated (started, progressed, or completed).
     * Used to refresh the book readers list.
     */
    data class ReadingSessionUpdated(
        val sessionId: String,
        val bookId: String,
        val isCompleted: Boolean,
        val listenTimeMs: Long,
        val finishedAt: String?,
    ) : BookEvent
}
