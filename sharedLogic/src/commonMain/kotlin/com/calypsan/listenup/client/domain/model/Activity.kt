package com.calypsan.listenup.client.domain.model

/**
 * Domain model for an activity feed item.
 *
 * Activities represent social events like starting a book, finishing a book,
 * or listening milestones. They are displayed in the Activity Feed on the
 * Discover screen.
 *
 * @property id Unique identifier
 * @property type Activity type (started_book, finished_book, streak_milestone, listening_milestone, shelf_created, listening_session)
 * @property userId User who performed the activity
 * @property createdAtMs When the activity occurred
 * @property user User display information
 * @property book Book information (if applicable)
 * @property isReread Whether this is a re-read/re-listen
 * @property durationMs Duration for listening sessions
 * @property milestoneValue Milestone value (hours listened, days streak, etc.)
 * @property milestoneUnit Milestone unit (hours, days, etc.)
 * @property shelfId Shelf ID (for shelf_created activities)
 * @property shelfName Shelf name (for shelf_created activities)
 */
data class Activity(
    val id: String,
    val type: String,
    val userId: String,
    val createdAtMs: Long,
    val user: ActivityUser,
    val book: ActivityBook?,
    val isReread: Boolean,
    val durationMs: Long,
    val milestoneValue: Int,
    val milestoneUnit: String?,
    val shelfId: String?,
    val shelfName: String?,
) {
    /**
     * User info for activity display.
     */
    data class ActivityUser(
        val displayName: String,
        val avatarColor: String,
        val avatarType: String,
        val avatarValue: String?,
    )

    /**
     * Book info for activity display.
     */
    data class ActivityBook(
        val id: String,
        val title: String,
        val authorName: String?,
        val coverPath: String?,
    )
}
