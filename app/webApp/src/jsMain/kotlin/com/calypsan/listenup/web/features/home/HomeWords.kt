package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.presentation.home.TimeOfDay
import com.calypsan.listenup.client.presentation.home.groupedCount

/**
 * Every word Home shows on the web, in one place. The web keeps no string catalog; these mirror `en.json`'s `home.*`
 * English, one wording per string, so the three platforms say the same thing.
 */
internal object HomeWords {
    const val HOME = "Home"
    const val WELCOME = "Welcome"
    const val WELCOME_HEADLINE = "Pick something new"
    const val WELCOME_BODY = "Start one and it lives here, ready to pick up where you left off."
    const val GETTING_STARTED = "Getting started"
    const val NOTHING_IN_PROGRESS = "Nothing in progress"
    const val NEXT_IN_A_SERIES = "Next in a series you're reading"
    const val BROWSE_LIBRARY = "Browse the Library"
    const val DISCOVER = "Discover"
    const val PLAY = "Play"
    const val YOU_FINISHED_A_BOOK = "You finished a book"
    const val DISMISS_EVERYWHERE = "Dismiss on all your devices"
    const val PLAY_NEXT_BOOK = "Play next book"
    const val HOW_WAS_IT = "How was it?"
    const val BUILDING_TITLE = "Building your library"
    const val ARRIVING_BODY = "Your place in each book is already here; covers and titles are arriving."
    const val UP_NEXT_LOADING = "Up next, loading"
    const val LOADING_BOOKS = "Loading books"
    const val LOADING_SHELVES = "Loading shelves"
    const val SEE_ALL = "See all"
    const val SEE_ALL_SHELVES = "See all shelves"
    const val SELECT = "Select"
    const val OFFLINE_NOTICE = "Can't reach your server."
    const val OFFLINE_NOTICE_BODY = "Your library is here; playing needs the connection."
    const val RETRY = "Retry"
    const val TRY_AGAIN = "Try again"
    const val COULDNT_LOAD_HOME = "We couldn't load Home."
    const val COULDNT_LOAD_LISTENING = "Couldn't load what you're listening to"
    const val YOUR_LAST_7_DAYS = "Your last 7 days"
    const val STATS_INVITATION_BODY =
        "Your listening time, streak and favourite genres fill in here after your first book."
    const val COULDNT_LOAD_STATS = "Couldn't load your stats."

    /** The part of the day, as the greeting opens. */
    fun greeting(timeOfDay: TimeOfDay): String =
        when (timeOfDay) {
            TimeOfDay.Morning -> "Good morning"
            TimeOfDay.Afternoon -> "Good afternoon"
            TimeOfDay.Evening -> "Good evening"
            TimeOfDay.Night -> "Good night"
        }

    /** "Play next book: Words of Radiance" — the button's accessible name. */
    fun playNextBook(title: String): String = "$PLAY_NEXT_BOOK: $title"

    /** "Finished today", "Finished yesterday", "Finished 3 days ago". */
    fun finished(daysAgo: Int): String =
        when (daysAgo) {
            0 -> "Finished today"
            1 -> "Finished yesterday"
            else -> "Finished $daysAgo days ago"
        }

    /** "45h 30m · 6 Sep – 10 Oct", or the length alone when the start is unknown (open question 8). */
    fun finishedMeta(
        length: String,
        startedOn: String?,
        finishedOn: String,
    ): String = if (startedOn == null) length else "$length · $startedOn – $finishedOn"

    /** "Maya, Tom and 3 more finished it too" (C9), or null when nobody is named (open question 7). */
    fun alsoFinished(
        names: List<String>,
        more: Int,
    ): String? {
        val named = names.filter { it.isNotBlank() }
        return when {
            named.isEmpty() -> null
            named.size == 1 && more == 0 -> "${named[0]} finished it too"
            named.size == 1 -> "${named[0]} and $more more finished it too"
            more == 0 -> "${named[0]} and ${named[1]} finished it too"
            else -> "${named[0]}, ${named[1]} and $more more finished it too"
        }
    }

    /** "Stormlight Archive, Book 2 · 48h 15m", or "Stormlight Archive · 48h 15m" without a number. */
    fun seriesLine(
        series: String,
        sequence: String?,
        length: String,
    ): String = if (sequence == null) "$series · $length" else "$series, Book $sequence · $length"

    /** "Building your library · 412 of 1,280 books": the strip's title while a scan knows both numbers (Q1). */
    fun arrivingTitle(
        done: Int,
        total: Int,
    ): String = "$BUILDING_TITLE · ${groupedCount(done)} of ${groupedCount(total)} books"

    /** "Read by Michael Kramer & Kate Reading". */
    fun readBy(narrators: String): String = "Read by $narrators"
}
