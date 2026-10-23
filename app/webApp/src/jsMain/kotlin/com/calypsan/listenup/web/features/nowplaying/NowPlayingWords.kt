package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerChapter
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

/** C6, the one place the counter's rule lives: only beside a real title, never for a chapterless book. */
private fun showsCounter(chapter: PlayerChapter): Boolean = !chapter.isWholeBook && !chapter.isGenericTitle
