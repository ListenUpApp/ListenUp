package com.calypsan.listenup.api.dto.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Canonical activity-type strings (wire-stable). */
object ActivityType {
    const val STARTED_BOOK = "started_book"
    const val FINISHED_BOOK = "finished_book"
    const val LISTENING_SESSION = "listening_session"
    const val STREAK_MILESTONE = "streak_milestone"
    const val LISTENING_MILESTONE = "listening_milestone"
    const val SHELF_CREATED = "shelf_created"
    const val USER_JOINED = "user_joined"
    const val CAMPFIRE_TOGETHER = "campfire_together"
}

/**
 * One social activity in the cross-user feed, as seen by a viewer. Book/shelf DISPLAY fields
 * (title/cover) are NOT on the wire — the client enriches them from local Room. Identity is
 * projected from `public_profiles`. The server returns only activity the caller may see
 * (book-bearing items for inaccessible books are omitted).
 *
 * @property id Stable activity id.
 * @property userId The acting user.
 * @property displayName The acting user's public display name.
 * @property avatarType `"auto"` or `"image"`.
 * @property type One of [ActivityType].
 * @property occurredAtMs Epoch-ms of the real event; also the pagination cursor (`before`).
 * @property bookId The book (for book-bearing types); guaranteed caller-accessible.
 * @property isReread True for a re-read `started_book`.
 * @property durationMs Session duration (for `listening_session`).
 * @property milestoneValue The milestone (days or hours).
 * @property milestoneUnit `"days"` or `"hours"`.
 * @property shelfId The shelf (for `shelf_created`).
 * @property shelfName The shelf name (for `shelf_created`).
 */
@Serializable
@SerialName("ActivityEvent")
data class ActivityEvent(
    val id: String,
    val userId: String,
    val displayName: String,
    val avatarType: String,
    val type: String,
    val occurredAtMs: Long,
    val bookId: String? = null,
    val isReread: Boolean = false,
    val durationMs: Long = 0L,
    val milestoneValue: Int = 0,
    val milestoneUnit: String? = null,
    val shelfId: String? = null,
    val shelfName: String? = null,
)
