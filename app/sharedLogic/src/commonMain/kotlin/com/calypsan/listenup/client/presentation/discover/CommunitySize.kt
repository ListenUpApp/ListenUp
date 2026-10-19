package com.calypsan.listenup.client.presentation.discover

/**
 * Who else Discover's social sections can draw on, as the reader sees it.
 *
 * Discover is about the other people on the server. When there are none, every social section has to
 * say so honestly (an invitation, "Just you so far") instead of rendering as broken or empty, and
 * before the roster has synced it must not claim either.
 */
sealed interface CommunitySize {
    /** The roster has not synced yet: show the normal skeletons rather than guess. */
    data object Unknown : CommunitySize

    /** The reader is the only person on the server. */
    data object JustYou : CommunitySize

    /** At least one other person is on the server. */
    data object Others : CommunitySize
}

/**
 * The [CommunitySize] of a roster of user ids, from [currentUserId]'s side. A roster that has not been pulled
 * ([rosterUserIds] null) is [CommunitySize.Unknown], and so is an empty one: a signed-in reader always has their own
 * row. This is the one place "Unknown" is decided; the screens only read the result.
 */
internal fun communitySizeOf(
    rosterUserIds: List<String>?,
    currentUserId: String,
): CommunitySize =
    when {
        rosterUserIds.isNullOrEmpty() -> CommunitySize.Unknown
        rosterUserIds.any { it != currentUserId } -> CommunitySize.Others
        else -> CommunitySize.JustYou
    }
