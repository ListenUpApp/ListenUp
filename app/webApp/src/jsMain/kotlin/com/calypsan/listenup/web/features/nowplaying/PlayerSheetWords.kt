package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import kotlin.time.Duration.Companion.milliseconds

/**
 * The player panels' words (spec §5.5): speed, sleep, boost, chapters and people. Web keeps no string catalog; these
 * mirror the sentences `en.json` gives the native clients as `player.sheet_*`. Kept apart from [NowPlayingWords], the
 * page's own words, so each object stays one concern.
 */
internal object PlayerSheetWords {
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
     * "Chapter 6, Bridge Four, now playing, 8 minutes left": the row's "8m" in words, as a screen reader should say it.
     */
    fun chapterRowLabel(
        row: PlayerChapterRow,
        isCurrent: Boolean,
        remainingMs: Long = 0,
    ): String =
        if (isCurrent) {
            "Chapter ${row.number}, ${row.title}, now playing, ${spokenDuration(remainingMs)} left"
        } else {
            "Play from chapter ${row.number}: ${row.title}, ${formatElapsed(row.durationMs)}"
        }
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
