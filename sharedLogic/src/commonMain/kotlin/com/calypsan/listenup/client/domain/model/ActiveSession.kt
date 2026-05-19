package com.calypsan.listenup.client.domain.model

/**
 * Domain model for an active reading session.
 *
 * Represents another user currently listening to a book.
 * Used in the "What Others Are Listening To" section on Discover.
 *
 * @property sessionId Unique session identifier
 * @property userId User who is listening
 * @property bookId Book being listened to
 * @property startedAtMs When the session started
 * @property updatedAtMs Last activity timestamp
 * @property user User display information
 * @property book Book display information
 */
data class ActiveSession(
    val sessionId: String,
    val userId: String,
    val bookId: String,
    val startedAtMs: Long,
    val updatedAtMs: Long,
    val user: SessionUser,
    val book: SessionBook,
) {
    /**
     * User info for session display.
     */
    data class SessionUser(
        val displayName: String,
        val avatarType: String,
        val avatarValue: String?,
        val avatarColor: String,
    )

    /**
     * Book info for session display.
     */
    data class SessionBook(
        val id: String,
        val title: String,
        val coverPath: String?,
        val coverBlurHash: String?,
        val authorName: String?,
    )
}
