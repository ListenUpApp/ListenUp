package com.calypsan.listenup.web.features.discover

/**
 * Discover's words on the web, mirroring `discover.*` in `en.json` (the web keeps no string catalog;
 * `MiniPlayerWords` is the precedent). One function per sentence that takes numbers, so the plural and
 * the empty case live here and not in the markup.
 */
internal object DiscoverWords {
    const val LISTENING_NOW = "Listening now"
    const val LIVE = "Live"
    const val SOMETHING_NEW = "Something new"
    const val SOMETHING_NEW_SUBTITLE = "Books you haven't started, first in their series"
    const val SHUFFLE = "Shuffle"
    const val SHUFFLE_A11Y = "Shuffle: draw ten different books"
    const val STANDALONE = "Standalone"
    const val LEADERBOARD = "Leaderboard"
    const val PERIOD_GROUP = "Leaderboard period"
    const val CATEGORY_GROUP = "Rank by"
    const val YOU = "You"
    const val RANK = "Rank "
    const val ACTIVITY = "Activity"
    const val SEE_ALL_ACTIVITY = "See all activity →"
    const val SHOW_LESS = "Show less"
    const val SHELVES_BY_OTHERS = "Shelves by others"
    const val RECENTLY_ADDED = "Recently added"
    const val NEWEST_IN_LIBRARY = "Newest in Library →"
    const val PAGE_SUBTITLE = "What the people on this server are listening to, and something new for you"

    /** "2 listening now · 3 more in the last two weeks"; null when nobody is shown. */
    fun listeningCount(
        live: Int,
        recent: Int,
    ): String? =
        when {
            live == 0 && recent == 0 -> null
            recent == 0 -> "$live listening now"
            live == 0 -> "$recent in the last two weeks"
            else -> "$live listening now · $recent more in the last two weeks"
        }

    /** "Book 1 of Mistborn", the bare series when unnumbered, or "Standalone". */
    fun seriesLine(
        series: String?,
        sequence: String?,
    ): String =
        when {
            series == null -> STANDALONE
            sequence == null -> series
            else -> "Book $sequence of $series"
        }

    /** A listener card's accessible name: the book first, then who is on it and when. */
    fun listenerLabel(
        book: String,
        name: String,
        isLive: Boolean,
        whenText: String,
    ): String = if (isLive) "$book, $name is listening now" else "$book, $name listened $whenText"

    /** "Show all 9". */
    fun showAll(count: Int): String = "Show all $count"

    /** "1 shelf", "2 shelves". */
    fun shelfCount(count: Int): String = if (count == 1) "1 shelf" else "$count shelves"

    /** "Added 2 days ago". */
    fun added(whenText: String): String = "Added $whenText"

    /** An avatar link's name: "Maya Chen's profile", or "Your profile" on your own row. */
    fun profileLabel(
        name: String,
        isYou: Boolean,
    ): String = if (isYou) "Your profile" else "$name's profile"
}
