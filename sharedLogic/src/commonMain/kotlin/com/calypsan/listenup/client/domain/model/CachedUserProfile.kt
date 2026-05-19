package com.calypsan.listenup.client.domain.model

/**
 * Cached profile data for displaying user avatars.
 *
 * This is a lightweight model containing just the essential fields
 * needed to display a user's avatar in lists (activity feed, readers, etc.).
 * For full profile data with stats, use [UserProfile].
 */
data class CachedUserProfile(
    val id: String,
    val displayName: String,
    val avatarType: String,
    val avatarValue: String?,
    val avatarColor: String,
)
