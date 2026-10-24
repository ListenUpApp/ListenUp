package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapter_number
import listenup.composeapp.generated.resources.player_chapter_of
import listenup.composeapp.generated.resources.player_np_whole_book
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

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
