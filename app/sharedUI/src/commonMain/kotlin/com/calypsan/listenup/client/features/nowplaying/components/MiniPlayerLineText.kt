package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.presentation.error.playerTitle
import com.calypsan.listenup.client.presentation.player.MiniPlayerLine
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_time_left
import listenup.composeapp.generated.resources.player_mini_auth_session_expired
import listenup.composeapp.generated.resources.player_mini_book_held_for_review
import listenup.composeapp.generated.resources.player_mini_chapter
import listenup.composeapp.generated.resources.player_mini_fading_out
import listenup.composeapp.generated.resources.player_mini_finished
import listenup.composeapp.generated.resources.player_mini_loading
import listenup.composeapp.generated.resources.player_mini_paused
import listenup.composeapp.generated.resources.player_mini_playback_autoplay_blocked
import listenup.composeapp.generated.resources.player_mini_playback_blocked_in_background
import listenup.composeapp.generated.resources.player_mini_playback_no_longer_available
import listenup.composeapp.generated.resources.player_mini_playback_offline_not_downloaded
import listenup.composeapp.generated.resources.player_mini_playback_server_unreachable
import listenup.composeapp.generated.resources.player_mini_playing_on
import listenup.composeapp.generated.resources.player_mini_sleep
import listenup.composeapp.generated.resources.player_mini_starting
import listenup.composeapp.generated.resources.player_mini_whole_book
import listenup.composeapp.generated.resources.player_reconnecting
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/** Which parts of [MiniPlayerLine] a surface has room for (spec §5.1 M6/M7). */
internal enum class MiniLineFit {
    /** The phone bar and the reader strip: "Bridge Four · 8m left"; an armed timer: "Bridge Four · Sleep · 23m". */
    WithTimeLeft,

    /** The tablet toolbar: "Bridge Four". Its times sit beside the scrubber, its timer on the moon. */
    WithoutTime,
}

/**
 * The mini player's second line. A leaf on purpose: only the time-left part reads [progress] and
 * [sleepRemainingMs], so a position tick recomposes this text and nothing around it. [sleepRemainingMs] is the armed
 * timer's wall-clock countdown (`SleepCountdowns.remainingMs`), read only when the line says a timer is armed.
 */
@Composable
internal fun MiniPlayerLineText(
    line: MiniPlayerLine,
    progress: () -> PlayerProgress,
    sleepRemainingMs: () -> Long?,
    fit: MiniLineFit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = miniLineWords(line, progress, sleepRemainingMs, fit),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // Announced once, politely, when the connection drops: the line holds no ticking time in that state.
        modifier =
            if (line == MiniPlayerLine.Reconnecting) {
                modifier.semantics { liveRegion = LiveRegionMode.Polite }
            } else {
                modifier
            },
    )
}

@Composable
private fun miniLineWords(
    line: MiniPlayerLine,
    progress: () -> PlayerProgress,
    sleepRemainingMs: () -> Long?,
    fit: MiniLineFit,
): String =
    when (line) {
        is MiniPlayerLine.Chapter -> {
            joinDots(chapterWords(line.number, line.title), trailing(line.sleepArmed, progress, sleepRemainingMs, fit))
        }

        is MiniPlayerLine.Paused -> {
            joinDots(
                stringResource(Res.string.player_mini_paused),
                chapterWords(line.number, line.title),
                trailing(line.sleepArmed, progress, sleepRemainingMs, fit),
            )
        }

        is MiniPlayerLine.Casting -> {
            joinDots(
                stringResource(Res.string.player_mini_playing_on, line.deviceName),
                chapterWords(line.number, line.title),
            )
        }

        MiniPlayerLine.Starting -> {
            stringResource(Res.string.player_mini_starting)
        }

        MiniPlayerLine.Loading -> {
            stringResource(Res.string.player_mini_loading)
        }

        MiniPlayerLine.Reconnecting -> {
            stringResource(Res.string.player_reconnecting)
        }

        MiniPlayerLine.FadingOut -> {
            stringResource(Res.string.player_mini_fading_out)
        }

        MiniPlayerLine.Finished -> {
            stringResource(Res.string.player_mini_finished)
        }

        is MiniPlayerLine.Problem -> {
            line.error.miniPlayerWords()
        }
    }

/**
 * The chapter as the book names it ("Bridge Four", "Chapter 14"), "Chapter 6" for an untitled chapter, or
 * "Whole book" for a chapterless one. Never our counter beside a title: it counts front matter.
 */
@Composable
private fun chapterWords(
    number: Int?,
    title: String?,
): String =
    when {
        title != null -> title
        number != null -> stringResource(Res.string.player_mini_chapter, number)
        else -> stringResource(Res.string.player_mini_whole_book)
    }

/** The time left in the chapter, or a running timer in its place; nothing where the surface shows times elsewhere. */
@Composable
private fun trailing(
    sleepArmed: Boolean,
    progress: () -> PlayerProgress,
    sleepRemainingMs: () -> Long?,
    fit: MiniLineFit,
): String? {
    if (fit == MiniLineFit.WithoutTime) return null
    val countdown = if (sleepArmed) sleepRemainingMs() else null
    return if (countdown != null) {
        stringResource(Res.string.player_mini_sleep, shortDuration(countdown))
    } else {
        stringResource(Res.string.book_time_left, shortDuration(progress().chapterRemainingMs))
    }
}

/** The short mini-player line for a book that can't play, or the card title where there is none (§4.6). */
@Composable
internal fun AppError.miniPlayerWords(): String =
    when (this) {
        is PlaybackError.ServerUnreachable -> stringResource(Res.string.player_mini_playback_server_unreachable)
        is PlaybackError.OfflineNotDownloaded -> stringResource(Res.string.player_mini_playback_offline_not_downloaded)
        is AuthError.SessionExpired -> stringResource(Res.string.player_mini_auth_session_expired)
        is PlaybackError.NoLongerAvailable -> stringResource(Res.string.player_mini_playback_no_longer_available)
        is BookError.HeldForReview -> stringResource(Res.string.player_mini_book_held_for_review)
        is PlaybackError.AutoplayBlocked -> stringResource(Res.string.player_mini_playback_autoplay_blocked)
        is PlaybackError.BlockedInBackground -> stringResource(Res.string.player_mini_playback_blocked_in_background)
        else -> playerTitle()
    }

private fun shortDuration(ms: Long): String =
    DurationFormatter.hoursMinutesOrUnderMinute(ms.coerceAtLeast(0).milliseconds)

private fun joinDots(vararg parts: String?): String = parts.filterNotNull().joinToString(" · ")
