package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerSettings
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl

/** A ten-minute book: long enough for every label to be a real time, short enough to read. */
internal const val FIXTURE_BOOK_MS = 600_000L

/**
 * A loaded book for the web player's DOM specs, the same shape the shared view model emits. Every knob a spec
 * varies is a parameter; the rest are plausible and fixed. [currentChapterIndex] picks the current row of
 * [chapters]; with no chapters (or no index) the book is one whole-book chapter, as the model says.
 */
@Suppress("LongParameterList") // one knob per thing a spec varies
internal fun loadedPlayerState(
    title: String = "Dune",
    bookId: String = "book-1",
    phase: PlayerPhase = PlayerPhase.Playing,
    primary: PrimaryControl = primaryFor(phase),
    authors: List<PlayerContributor> = listOf(PlayerContributor("c1", "Frank Herbert", 1)),
    narrators: List<PlayerContributor> = emptyList(),
    series: List<PlayerSeriesRef> = emptyList(),
    coverHash: String? = null,
    durationMs: Long = FIXTURE_BOOK_MS,
    chapters: List<PlayerChapterRow> = emptyList(),
    currentChapterIndex: Int? = null,
    speed: Float = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
    defaultSpeed: Float = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
    boostDb: Float = PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB,
    defaultBoostDb: Float = PlaybackPreferences.DEFAULT_VOLUME_BOOST_DB,
    skipBackSec: Int = PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC,
    skipForwardSec: Int = PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC,
    sleepTimer: SleepTimerState = SleepTimerState.Inactive,
    showsSkipBack: Boolean =
        phase == PlayerPhase.Playing || phase == PlayerPhase.Paused || phase == PlayerPhase.Restored,
): PlayerUiState.Loaded {
    val rows = chapters.map { it.copy(isCurrent = it.index == currentChapterIndex) }
    val current = rows.firstOrNull { it.isCurrent }
    val chapter =
        current?.let {
            PlayerChapter(
                index = it.index,
                number = it.number,
                total = rows.size,
                title = it.title,
                isGenericTitle = false,
                isWholeBook = false,
                startMs = it.startMs,
                durationMs = it.durationMs,
            )
        } ?: PlayerChapter(0, 1, 1, "", isGenericTitle = true, isWholeBook = true, startMs = 0, durationMs = durationMs)
    return PlayerUiState.Loaded(
        book =
            PlayerBook(
                id = bookId,
                title = title,
                authors = authors,
                narrators = narrators,
                coverPath = null,
                coverHash = coverHash,
                series = series,
                durationMs = durationMs,
                firstPdfDocId = null,
            ),
        phase = phase,
        chapter = chapter,
        chapters = rows,
        source = AudioSource.Streaming,
        settings = PlayerSettings(speed, defaultSpeed, boostDb, defaultBoostDb, skipBackSec, skipForwardSec),
        sleepTimer = sleepTimer,
        primary = primary,
        showsSkipBack = showsSkipBack,
        canSkipToNextChapter = !chapter.isWholeBook && chapter.number < chapter.total,
        finished = null,
    )
}

/** [count] chapter rows, [spacingMs] apart, titled "Chapter 1", "Chapter 2", …; none current yet. */
internal fun chapterRows(
    count: Int,
    spacingMs: Long = 1_000L,
): List<PlayerChapterRow> =
    (0 until count).map {
        PlayerChapterRow(
            index = it,
            number = it + 1,
            title = "Chapter ${it + 1}",
            startMs = it * spacingMs,
            durationMs = spacingMs,
            isCurrent = false,
        )
    }

/** A playhead at [positionMs] of [durationMs], book-scoped (what the web bar reads until PR 3). */
internal fun playerProgress(
    positionMs: Long = 0L,
    durationMs: Long = FIXTURE_BOOK_MS,
): PlayerProgress =
    PlayerProgress(
        chapterPositionMs = positionMs,
        chapterDurationMs = durationMs,
        bookPositionMs = positionMs,
        bookDurationMs = durationMs,
    )

/** The main button the shared model would choose for [phase], for specs that do not care which error. */
private fun primaryFor(phase: PlayerPhase): PrimaryControl =
    when (phase) {
        PlayerPhase.Playing, PlayerPhase.SleepFading -> PrimaryControl.Pause
        PlayerPhase.Paused, PlayerPhase.Restored -> PrimaryControl.Play
        PlayerPhase.Preparing, PlayerPhase.Buffering -> PrimaryControl.Busy
        PlayerPhase.Finished -> PrimaryControl.ListenAgain
        is PlayerPhase.Failed, is PlayerPhase.Unavailable -> PrimaryControl.Retry
    }
