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
 * The [CommunitySize] of a synced roster of user ids, from [currentUserId]'s side. An empty roster has
 * not synced (a signed-in reader always has their own row), so it is [CommunitySize.Unknown].
 */
internal fun communitySizeOf(
    rosterUserIds: List<String>,
    currentUserId: String,
): CommunitySize =
    when {
        rosterUserIds.isEmpty() -> CommunitySize.Unknown
        rosterUserIds.any { it != currentUserId } -> CommunitySize.Others
        else -> CommunitySize.JustYou
    }
