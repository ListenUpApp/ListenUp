package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.dto.image.ImageFrame

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
    /**
     * Last-update timestamp (epoch ms). The server bumps it whenever the profile changes —
     * including on avatar upload — so it doubles as the avatar's content version: folded into
     * the Coil cache key, a re-uploaded avatar busts the stale cached bitmap instead of
     * rendering the old one.
     */
    val updatedAt: Long,
    /** How the avatar image is framed; null shows the centre crop. Already checked against the stored avatar's version. */
    val avatarFrame: ImageFrame? = null,
)
