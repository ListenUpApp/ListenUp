package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapter_number
import listenup.composeapp.generated.resources.player_chapter_of
import listenup.composeapp.generated.resources.player_np_whole_book
import listenup.composeapp.generated.resources.player_spoken_hours
import listenup.composeapp.generated.resources.player_spoken_hours_minutes
import listenup.composeapp.generated.resources.player_spoken_hours_one
import listenup.composeapp.generated.resources.player_spoken_minutes
import listenup.composeapp.generated.resources.player_spoken_minutes_one
import listenup.composeapp.generated.resources.player_spoken_under_a_minute
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Audio is advancing: the wave moves and the scrubber animates. */
internal val PlayerUiState.Loaded.isAudible: Boolean
    get() = phase == PlayerPhase.Playing || phase == PlayerPhase.SleepFading

/** Preparing or buffering: the main button spins and the scrubber holds still. */
internal val PlayerUiState.Loaded.isBusy: Boolean
    get() = primary == PrimaryControl.Busy

/** The byline as one line ("A, B"), for cover placeholders. */
internal val PlayerBook.authorLine: String
    get() = authors.joinToString(", ") { it.name }

/**
 * "Chapter 6 of 75" (P7), or null where it must not show: a chapterless book, or a generic title, which our counter
 * could contradict ("Chapter 16 of 75 · Chapter 14"; Simon, 2026-10-14). Open question 1's default (C6).
 */
@Composable
internal fun PlayerUiState.Loaded.chapterCounter(): String? =
    if (chapter.isWholeBook || chapter.isGenericTitle) {
        null
    } else {
        stringResource(Res.string.player_chapter_of, chapter.number.toString(), chapter.total.toString())
    }

/** The chapter's own title; "Whole book" for a chapterless book, "Chapter N" for an untitled one (§3.2). */
@Composable
internal fun PlayerUiState.Loaded.chapterTitleLine(): String =
    when {
        chapter.isWholeBook -> stringResource(Res.string.player_np_whole_book)
        chapter.title.isBlank() -> stringResource(Res.string.player_chapter_number, chapter.number)
        else -> chapter.title
    }

/** The shared contributor rows and full-cast sheet take [BookContributor]s. */
internal fun List<PlayerContributor>.asBookContributors(): List<BookContributor> =
    map { BookContributor(id = it.id, name = it.name) }

/** A short remainder in content or wall time — "8m", "1h 5m", "<1m" — as the mini line and the sheets write it. */
internal fun shortDuration(ms: Long): String =
    DurationFormatter.hoursMinutesOrUnderMinute(ms.coerceAtLeast(0).milliseconds)

/** The time left in the current chapter, floored to the minute: it changes once a minute, as its words do. */
internal val PlayerProgress.chapterLeftToTheMinute: Duration
    get() =
        chapterRemainingMs
            .coerceAtLeast(0)
            .milliseconds.inWholeMinutes.minutes

/**
 * A duration as TalkBack should say it — "8 minutes", "1 hour 5 minutes", "less than a minute" — where the screen
 * writes "8m". Floored to the minute, like the visible words, and never "0 minutes", which a listener would hear as
 * "already over".
 */
@Composable
internal fun spokenDuration(duration: Duration): String {
    val hours = duration.inWholeHours.coerceAtLeast(0).toInt()
    val minutes = (duration.inWholeMinutes % MINUTES_PER_HOUR).coerceAtLeast(0).toInt()
    val hoursWords =
        when (hours) {
            0 -> null
            1 -> stringResource(Res.string.player_spoken_hours_one)
            else -> stringResource(Res.string.player_spoken_hours, hours)
        }
    val minutesWords =
        when (minutes) {
            0 -> null
            1 -> stringResource(Res.string.player_spoken_minutes_one)
            else -> stringResource(Res.string.player_spoken_minutes, minutes)
        }
    return when {
        hoursWords != null && minutesWords != null -> {
            stringResource(Res.string.player_spoken_hours_minutes, hoursWords, minutesWords)
        }

        else -> {
            hoursWords ?: minutesWords ?: stringResource(Res.string.player_spoken_under_a_minute)
        }
    }
}

private const val MINUTES_PER_HOUR = 60
