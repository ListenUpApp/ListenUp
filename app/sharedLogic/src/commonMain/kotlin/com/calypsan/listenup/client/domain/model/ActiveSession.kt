package com.calypsan.listenup.client.domain.model

/**
 * Domain model for one row of "What Others Are Listening To" on Discover.
 *
 * Either another user is listening **right now** ([isLive]), or — because on a small server that is
 * rarely true of anyone — the row shows the book they most recently played and did not finish. Both
 * kinds carry exactly one timestamp, [lastActiveAtMs], so no field is meaningless for half the rows.
 *
 * @property sessionId Stable identity for this row (`"$userId:$bookId"`)
 * @property userId The other user
 * @property bookId The book shown for them
 * @property lastActiveAtMs When they were last active on [bookId]: the session start when [isLive],
 *   otherwise when they last played it
 * @property isLive True when they are listening right now
 * @property user User display information
 * @property book Book display information
 */
data class ActiveSession(
    val sessionId: String,
    val userId: String,
    val bookId: String,
    val lastActiveAtMs: Long,
    val isLive: Boolean,
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
        val coverHash: String? = null,
        val authorName: String?,
    )
}
