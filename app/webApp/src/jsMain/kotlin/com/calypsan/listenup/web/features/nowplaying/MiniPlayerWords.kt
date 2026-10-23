package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.player.MiniPlayerLine
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import kotlin.time.Duration.Companion.milliseconds

/** Which parts of the line the bar shows (spec §5.1 M6/M7). */
internal enum class MiniLineFit {
    /** ≥ 760px: "Bridge Four · Brandon Sanderson"; the times sit by the scrubber, a timer on the moon. */
    Desktop,

    /** < 760px: "Bridge Four · 8m left"; an armed timer: "Bridge Four · Sleep · 23m". */
    Phone,
}

/**
 * The bar's second line in the browser's words. Web keeps no string catalog; these mirror `en.json`'s
 * `player.mini_*`, as [playerTitle] mirrors `player.title_*`.
 *
 * A chapter is named by the book's own title, never by our counter: the counter includes front matter, so it can
 * disagree with what the book calls the chapter. Only an untitled chapter falls back to its number.
 *
 * [sleepRemainingMs] is the armed timer's wall-clock countdown (`SleepCountdowns.remainingMs`); the line only says
 * that a timer is armed, so the bar supplies the number.
 */
internal fun miniLineText(
    line: MiniPlayerLine,
    progress: PlayerProgress,
    fit: MiniLineFit,
    authorLine: String,
    sleepRemainingMs: Long? = null,
): String =
    when (line) {
        is MiniPlayerLine.Chapter -> {
            joinDots(
                chapterWords(line.number, line.title),
                if (fit ==
                    MiniLineFit.Phone
                ) {
                    phoneTrailing(sleepRemainingMs.takeIf { line.sleepArmed }, progress)
                } else {
                    authorLine.ifBlank { null }
                },
            )
        }

        is MiniPlayerLine.Paused -> {
            joinDots(
                "Paused",
                chapterWords(line.number, line.title),
                if (fit == MiniLineFit.Phone) phoneTrailing(sleepRemainingMs.takeIf { line.sleepArmed }, progress) else null,
            )
        }

        is MiniPlayerLine.Casting -> {
            joinDots("Playing on ${line.deviceName}", chapterWords(line.number, line.title))
        }

        else -> {
            statusWords(line)
        }
    }

/**
 * The same line as a screen reader should hear it: commas rather than dots, and the time spelled out with what it
 * counts ("8 minutes left in the chapter", not "8m left"). The bar's body reads this after the book's title.
 */
internal fun miniLineLabel(
    line: MiniPlayerLine,
    progress: PlayerProgress,
    sleepRemainingMs: Long? = null,
): String =
    when (line) {
        is MiniPlayerLine.Chapter -> {
            joinCommas(chapterWords(line.number, line.title), spokenTrailing(sleepRemainingMs.takeIf { line.sleepArmed }, progress))
        }

        is MiniPlayerLine.Paused -> {
            joinCommas("Paused", chapterWords(line.number, line.title), spokenTrailing(sleepRemainingMs.takeIf { line.sleepArmed }, progress))
        }

        is MiniPlayerLine.Casting -> {
            joinCommas("Playing on ${line.deviceName}", chapterWords(line.number, line.title))
        }

        else -> {
            statusWords(line)
        }
    }

/** The short line for a book that can't play, or the card title where the player has none (§4.6). */
internal fun AppError.miniPlayerWords(): String =
    when (this) {
        is PlaybackError.ServerUnreachable -> "Can't reach your server"
        is PlaybackError.OfflineNotDownloaded -> "Can't play offline"
        is AuthError.SessionExpired -> "Signed out · place saved"
        is PlaybackError.NoLongerAvailable -> "No longer in your library"
        is BookError.HeldForReview -> "Not playable"
        is PlaybackError.AutoplayBlocked -> "Tap Play to start"
        is PlaybackError.BlockedInBackground -> "Tap to keep listening"
        else -> playerTitle()
    }

/**
 * A duration as words: "8 minutes", "1 hour 6 minutes", "less than a minute". Never "0 minutes", which a
 * listener would hear as "already over".
 */
internal fun spokenDuration(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0) / MILLIS_PER_MINUTE
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours > 0 && minutes > 0 -> "${counted(hours, "hour")} ${counted(minutes, "minute")}"
        hours > 0 -> counted(hours, "hour")
        minutes > 0 -> counted(minutes, "minute")
        else -> "less than a minute"
    }
}

/** The words of a line that carries no chapter: a transition, the end, or a problem. */
private fun statusWords(line: MiniPlayerLine): String =
    when (line) {
        MiniPlayerLine.Starting -> "Starting…"
        MiniPlayerLine.Loading -> "Loading…"
        MiniPlayerLine.Reconnecting -> "Reconnecting…"
        MiniPlayerLine.FadingOut -> "Sleep timer · fading out"
        MiniPlayerLine.Finished -> "Finished"
        is MiniPlayerLine.Problem -> line.error.miniPlayerWords()
        is MiniPlayerLine.Chapter, is MiniPlayerLine.Paused, is MiniPlayerLine.Casting -> ""
    }

private fun chapterWords(
    number: Int?,
    title: String?,
): String = title ?: number?.let { "Chapter $it" } ?: "Whole book"

private fun phoneTrailing(
    sleepRemainingMs: Long?,
    progress: PlayerProgress,
): String =
    sleepRemainingMs?.let { "Sleep · ${shortDuration(it)}" } ?: "${shortDuration(progress.chapterRemainingMs)} left"

private fun spokenTrailing(
    sleepRemainingMs: Long?,
    progress: PlayerProgress,
): String =
    sleepRemainingMs?.let { "sleep timer, ${spokenDuration(it)} left" }
        ?: "${spokenDuration(progress.chapterRemainingMs)} left in the chapter"

internal fun shortDuration(ms: Long): String =
    DurationFormatter.hoursMinutesOrUnderMinute(ms.coerceAtLeast(0).milliseconds)

private fun counted(
    count: Long,
    unit: String,
): String = if (count == 1L) "1 $unit" else "$count ${unit}s"

private fun joinDots(vararg parts: String?): String = parts.filterNotNull().joinToString(" · ")

private fun joinCommas(vararg parts: String?): String = parts.filterNotNull().joinToString(", ")

private const val MILLIS_PER_MINUTE = 60_000L

private const val MINUTES_PER_HOUR = 60L
