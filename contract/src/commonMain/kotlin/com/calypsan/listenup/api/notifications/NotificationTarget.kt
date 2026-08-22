package com.calypsan.listenup.api.notifications

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where a tap on a notification lands, in domain terms — the contract cannot know about client
 * route types, so each client maps these to its own navigation in ONE exhaustive `when` (PR 2/3).
 * A new target is therefore a compile error on both clients until handled, never a dead tap.
 */
@Serializable
sealed interface NotificationTarget {
    /** Opens the book's detail screen. */
    @Serializable
    @SerialName("book")
    data class Book(
        @SerialName("bookId") val bookId: String,
    ) : NotificationTarget

    /** Opens a user's profile. */
    @Serializable
    @SerialName("profile")
    data class Profile(
        @SerialName("userId") val userId: String,
    ) : NotificationTarget

    /** Opens the campfire (shared listening session). */
    @Serializable
    @SerialName("campfire")
    data class Campfire(
        @SerialName("campfireId") val campfireId: String,
    ) : NotificationTarget

    /** Opens the admin approvals inbox. */
    @Serializable
    @SerialName("admin_inbox")
    data object AdminInbox : NotificationTarget

    /** Opens the app, nothing more — for types with nowhere meaningful to go. */
    @Serializable
    @SerialName("none")
    data object None : NotificationTarget
}
