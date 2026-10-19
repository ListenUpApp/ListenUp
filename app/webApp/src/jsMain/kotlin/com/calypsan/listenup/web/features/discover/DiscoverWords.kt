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
    const val ONLY_YOU_TITLE = "It's just you here so far"
    const val ONLY_YOU_BODY =
        "When other people join, you'll see what they're listening to, the shelves they share and what they finish."
    const val INVITE_SOMEONE = "Invite someone"
    const val NOBODY_LISTENING = "Nobody's listening right now. When someone starts, they'll show up here."
    const val STARTED_EVERYTHING = "You've started every book here."
    const val BROWSE_THE_LIBRARY = "Browse the Library"
    const val NO_NEW_BOOKS_YET = "Nothing new just yet."
    const val NO_RECENTLY_ADDED = "No books added recently."
    const val NO_ACTIVITY = "No activity yet. Start listening to see what your community is reading!"
    const val LEADERBOARD_JUST_YOU = "Just you so far. The board fills in as people join."
    const val LEADERBOARD_EMPTY_PERIOD = "Nobody listened in this period yet."
    const val SHELVES_NEED_CONNECTION = "Shelves by others need a connection"
    const val LISTENING_NEED_CONNECTION = "Who's listening needs a connection"
    const val SHELVES_ONLY_YOU = "Shelves people make public show up here."
    const val ERROR_LISTENING = "Couldn't load who's listening"
    const val ERROR_PICKS = "Couldn't load new books"
    const val ERROR_LEADERBOARD = "Couldn't load the leaderboard"
    const val ERROR_ACTIVITY = "Couldn't load activity"
    const val ERROR_SHELVES = "Couldn't load shelves by others"
    const val ERROR_RECENT = "Couldn't load recently added"

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

    /** An activity row's body link: what happened, then when ("Ada finished Dune by Frank Herbert, 2 hours ago"). */
    fun activityLabel(
        sentence: String,
        whenText: String,
    ): String = "$sentence, $whenText"

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

    /** "As of 2 hours ago": how old a roster is while the server cannot be reached. */
    fun listeningAsOf(whenText: String): String = "As of $whenText"
}
