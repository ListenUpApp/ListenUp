package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.isSameVolumeBoost
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Now Playing page's words. Web keeps no string catalog; these are the sentences `en.json` gives the native
 * clients (`player.np_*`, `player.chapter_of`, `home.how_was_it`, …), plus the few only the web page draws (the chapter
 * row's "Playing · 8m left", "Back to current chapter", the link labels).
 */
internal object NowPlayingWords {
    const val NOW_PLAYING = "Now playing"
    const val BY = "by"
    const val READ_BY = "Read by"
    const val PREVIOUS_HINT = "Goes to the start of this chapter first."
    const val PLACE_SAVED = "Your place is saved."
    const val HOW_WAS_IT = "How was it?"
    const val NEXT_IN_SERIES = "Next in the series"
    const val WHOLE_BOOK = "Whole book"
    const val BACK_TO_CURRENT = "Back to current chapter"
    const val COLLAPSE = "Collapse player"
    const val FADING_TITLE = "Fading out…"
    const val FADING_BODY = "Your sleep timer is ending. Playback pauses in a moment."
    const val BACK = "Back"
    const val CHAPTER_POSITION = "Chapter position"

    // The panels' words (en.json's `player.sheet_*`).
    const val PLAYBACK_SPEED = "Playback speed"
    const val SLOWER = "Slower by 0.05"
    const val FASTER = "Faster by 0.05"
    const val SPEED_PRESETS = "Speed presets"
    const val THIS_BOOK_ONLY = "Applies to this book only."
    const val SLEEP_TIMER = "Sleep timer"
    const val END_OF_CHAPTER = "End of chapter"
    const val DURATIONS = "Durations"
    const val CUSTOM_MINUTES = "Custom (minutes)"
    const val START = "Start"
    const val FADES_OUT = "Audio fades out over a few seconds before it pauses."
    const val ADD_TIME = "Add time"
    const val CANCEL_TIMER = "Cancel timer"
    const val VOLUME_BOOST = "Volume boost"
    const val BOOST_LEVEL = "Boost level"
    const val BOOST_NOTE = "Louder for quiet recordings. Applies to this book only."
    const val CHAPTERS = "Chapters"
    const val CLOSE = "Close"

    /** The status slot (§3.1), with the browser's wording for an unreachable server. */
    fun status(source: AudioSource): String =
        when (source) {
            AudioSource.Downloaded -> "Downloaded"
            AudioSource.DownloadedOffline -> "Downloaded · offline"
            AudioSource.Streaming -> "Streaming"
            AudioSource.StreamingTranscoded -> "Streaming · transcoded"
            AudioSource.Offline -> "Offline"
            AudioSource.ServerUnreachable -> "Can't reach your server"
            is AudioSource.Casting -> "Playing on ${source.deviceName}"
            AudioSource.NotPlayable -> "Not playable"
        }

    /** "Book 11% · 40h 31m left": the shared floored percent and the content time left in the book (§3.2). */
    fun bookSentence(progress: PlayerProgress): String =
        "Book ${progress.bookPercent}% · ${DurationFormatter.hoursMinutes(progress.bookRemainingMs.milliseconds)} left"

    /** "Chapter 6 of 75". */
    fun counter(
        number: Int,
        total: Int,
    ): String = "Chapter $number of $total"

    /**
     * The chapter line (C6): "Chapter 6 of 75 · Bridge Four" when the chapter has a real title; the book's own generic
     * title alone ("Chapter 14"), with no counter that could disagree with it; "Whole book" for a chapterless book.
     */
    fun chapterLine(chapter: PlayerChapter): String =
        when {
            chapter.isWholeBook -> WHOLE_BOOK
            showsCounter(chapter) -> "${counter(chapter.number, chapter.total)} · ${chapter.title}"
            else -> chapter.title.ifBlank { counter(chapter.number, chapter.total) }
        }

    /** The current chapter row's visible line: "Playing · 8m left", the bar's own minute wording. */
    fun playingLeft(remainingMs: Long): String = "Playing · ${shortDuration(remainingMs)} left"

    /** The Speed sheet's one wall-time figure (S10): "32h 24m left at this speed". */
    fun leftAtSpeed(wallMs: Long): String = "${shortDuration(wallMs)} left at this speed"

    /** "Reset to 1.0× (your default)": the speed reset names the listener's own default. */
    fun resetSpeed(speedText: String): String = "Reset to $speedText (your default)"

    /** "Reset to Off (your default)". */
    fun resetBoost(boostText: String): String = "Reset to $boostText (your default)"

    /** End of chapter's line: "Pauses after Bridge Four · in 8m", in content time (open question 2). */
    fun endOfChapterAfter(
        chapterTitle: String,
        chapterRemainingMs: Long,
    ): String = "Pauses after $chapterTitle · in ${shortDuration(chapterRemainingMs)}"

    /** A running end-of-chapter timer's line: "Until the end of Bridge Four · 8m". */
    fun untilEndOf(
        chapterTitle: String,
        chapterRemainingMs: Long,
    ): String = "Until the end of $chapterTitle · ${shortDuration(chapterRemainingMs)}"

    /**
     * "Pauses at 11:42 PM · 30 min timer", or "Pauses at 11:42 PM" without [minutes]: the browser's own clock format,
     * so a 24-hour locale reads "23:42" (C16).
     */
    fun pausesAt(
        epochMs: Long,
        minutes: Int?,
    ): String {
        val at = "Pauses at ${clockTime(epochMs)}"
        return if (minutes == null) at else "$at · ${SleepTimerMode.Duration(minutes).label} timer"
    }

    /** A sleep countdown: "23:41", "1:05:00" past an hour. */
    fun countdown(wallMs: Long): String = formatElapsed(wallMs)

    /** The gold meter's name: "23:41 of 30:00 remaining". */
    fun meterLabel(
        remainingMs: Long,
        totalMs: Long,
    ): String = "${countdown(remainingMs)} of ${countdown(totalMs)} remaining"

    /** "+5 min". */
    fun extend(minutes: Int): String = "+$minutes min"

    /** A person's books beside their name: "14 books", "1 book", and no line at all for none (C12). */
    fun books(count: Int): String? =
        when {
            count <= 0 -> null
            count == 1 -> "1 book"
            else -> "$count books"
        }

    /** The chapter list's header: "75 chapters · 45h 30m". */
    fun chaptersSummary(
        count: Int,
        bookDurationMs: Long,
    ): String = "$count chapters · ${DurationFormatter.hoursMinutes(bookDurationMs.milliseconds)}"

    /**
     * A chapter row's accessible name: "Play from chapter 7: Anything Reasonable, 33:18", or for the current one
     * "Chapter 6, Bridge Four, now playing, 8m left".
     */
    fun chapterRowLabel(
        row: PlayerChapterRow,
        isCurrent: Boolean,
        remainingMs: Long = 0,
    ): String =
        if (isCurrent) {
            "Chapter ${row.number}, ${row.title}, now playing, ${shortDuration(remainingMs)} left"
        } else {
            "Play from chapter ${row.number}: ${row.title}, ${formatElapsed(row.durationMs)}"
        }

    /** "8m left in chapter", under the book card's chapter line. */
    fun leftInChapter(remainingMs: Long): String = "${shortDuration(remainingMs)} left in chapter"

    /** A card's button. */
    fun action(action: PlayerCardAction): String =
        when (action) {
            PlayerCardAction.Retry -> "Retry"
            PlayerCardAction.Dismiss -> "Dismiss"
            PlayerCardAction.CloseBook -> "Close book"
            PlayerCardAction.SignIn -> "Sign in"
            PlayerCardAction.OpenInbox -> "Open Inbox"
            PlayerCardAction.Resume -> "Resume"
            PlayerCardAction.TapToPlay -> "Play"
            PlayerCardAction.ListenAgain -> "Listen again"
            PlayerCardAction.PlayNextBook -> "Play next book"
            PlayerCardAction.KeepListening -> "Keep listening"
        }

    /** The More menu's boost row: "Volume boost · Off" or "Volume boost · +6 dB". */
    fun boostRow(boostDb: Float): String =
        if (isSameVolumeBoost(boostDb, PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB)) {
            "Volume boost · Off"
        } else {
            "Volume boost · +${boostDb.toInt()} dB"
        }

    /** "Reference req-7f3a": the server's correlation id, for a report. */
    fun reference(id: String): String = "Reference $id"

    /** "Book 2": the next book's place in its series. */
    fun nextBook(sequence: String): String = "Book $sequence"

    /** "The Stormlight Archive · Book 1", or the name alone when the book has no place. */
    fun seriesLink(
        name: String,
        sequence: String?,
    ): String = sequence?.let { "$name · ${nextBook(it)}" } ?: name

    /** "Narrator 1 & 4 more": a byline of more than three. */
    fun fold(
        lead: String,
        more: Int,
    ): String = "$lead & $more more"

    /** The title link's name. */
    fun goToBook(title: String): String = "Go to book: $title"

    /** The More menu's trigger, named for the book (WCAG 2.4.6, Book Detail's precedent). */
    fun moreFor(title: String): String = "More actions for $title"
}

/**
 * [epochMs] as the browser writes a time of day in its own locale ("11:42 PM", "23:42"). The narrow and no-break spaces
 * some engines put before AM/PM become plain ones, so the line wraps and reads like the rest of the sheet.
 */
private fun clockTime(epochMs: Long): String {
    // A Double, not the Long: `js()` sees Kotlin's own Long representation, which `Date` cannot read.
    val ms = epochMs.toDouble()
    val date: dynamic = js("new Date(ms)")
    val options: dynamic = js("({ hour: 'numeric', minute: '2-digit' })")
    val text: String = date.toLocaleTimeString(undefined, options).unsafeCast<String>()
    return text.replace('\u202F', ' ').replace('\u00A0', ' ')
}

/** C6, the one place the counter's rule lives: only beside a real title, never for a chapterless book. */
private fun showsCounter(chapter: PlayerChapter): Boolean = !chapter.isWholeBook && !chapter.isGenericTitle
