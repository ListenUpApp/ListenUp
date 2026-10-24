package com.calypsan.listenup.client.features.nowplaying

import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.AudioSource
import com.calypsan.listenup.client.presentation.player.FinishedInfo
import com.calypsan.listenup.client.presentation.player.PlayerBook
import com.calypsan.listenup.client.presentation.player.PlayerChapter
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerSettings
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl

/** A loaded book for the player's UI tests: chapter 6 of 75 of a long book, two narrators. */
internal fun loadedPlayerState(
    title: String = "The Way of Kings",
    phase: PlayerPhase = PlayerPhase.Playing,
    primary: PrimaryControl = PrimaryControl.Pause,
    chapterTitle: String = "Bridge Four",
    coverPath: String? = null,
    skipBackSec: Int = 10,
    skipForwardSec: Int = 30,
    source: AudioSource = AudioSource.Streaming,
    sleepTimer: SleepTimerState = SleepTimerState.Inactive,
    isGenericTitle: Boolean = false,
    isWholeBook: Boolean = false,
    // The shared rule (`showsSkipBackFor` is internal to :app:sharedLogic): only a settled position offers back N.
    showsSkipBack: Boolean =
        phase == PlayerPhase.Playing ||
            phase == PlayerPhase.Paused ||
            phase == PlayerPhase.Restored ||
            phase == PlayerPhase.SleepFading,
    series: List<PlayerSeriesRef> = emptyList(),
    firstPdfDocId: String? = null,
    boostDb: Float = 0f,
    canSkipToNextChapter: Boolean = true,
    finished: FinishedInfo? = null,
    authors: List<PlayerContributor> = listOf(PlayerContributor("a1", "Brandon Sanderson", 14)),
    narrators: List<PlayerContributor> =
        listOf(
            PlayerContributor("n1", "Michael Kramer", 120),
            PlayerContributor("n2", "Kate Reading", 98),
        ),
): PlayerUiState.Loaded =
    PlayerUiState.Loaded(
        book =
            PlayerBook(
                id = "book-1",
                title = title,
                authors = authors,
                narrators = narrators,
                coverPath = coverPath,
                coverHash = null,
                series = series,
                durationMs = 163_800_000,
                firstPdfDocId = firstPdfDocId,
            ),
        phase = phase,
        chapter =
            if (isWholeBook) {
                PlayerChapter(
                    index = 0,
                    number = 1,
                    total = 1,
                    title = "",
                    isGenericTitle = true,
                    isWholeBook = true,
                    startMs = 0,
                    durationMs = 163_800_000,
                )
            } else {
                PlayerChapter(
                    index = 5,
                    number = 6,
                    total = 75,
                    title = chapterTitle,
                    isGenericTitle = isGenericTitle,
                    isWholeBook = false,
                    startMs = 0,
                    durationMs = 1_235_000,
                )
            },
        chapters =
            (0 until 75).map {
                PlayerChapterRow(it, it + 1, "Chapter ${it + 1}", it * 1_000L, 1_000L, isCurrent = it == 5)
            },
        source = source,
        settings = PlayerSettings(1.0f, 1.0f, boostDb, 0f, skipBackSec, skipForwardSec),
        sleepTimer = sleepTimer,
        primary = primary,
        showsSkipBack = showsSkipBack,
        canSkipToNextChapter = canSkipToNextChapter,
        finished = finished,
    )

/**
 * A cover path that does not exist: a cover with a path renders without reaching Koin for the server fallback, so a
 * test can draw the cover without a Koin graph.
 */
internal const val NO_COVER = "/nonexistent/cover.jpg"

/** A position partway through chapter 6. */
internal val samplePlayerProgress =
    PlayerProgress(
        chapterPositionMs = 724_000,
        chapterDurationMs = 1_235_000,
        bookPositionMs = 18_000_000,
        bookDurationMs = 163_800_000,
    )

/** 23:18:19 on the test's clock (the zone is the test's to set): with 23:41 left, the timer pauses at 11:42 PM. */
internal const val SLEEP_SHEET_NOW_MS: Long = (23 * 3_600 + 18 * 60 + 19) * 1_000L

/**
 * What the sleep sheet draws, for its tests: chapter 6, "Bridge Four", with 8 minutes of it left. A duration timer's
 * countdown and meter default to its own remainder and share of its total, as `SleepCountdowns` gives them.
 */
internal fun sleepSheetState(
    timer: SleepTimerState = SleepTimerState.Inactive,
    isWholeBook: Boolean = false,
    chapterRemainingMs: Long = 480_000,
    countdownMs: Long? = (timer as? SleepTimerState.Active)?.remainingMs,
    meterFraction: Float =
        (timer as? SleepTimerState.Active)?.takeIf { it.totalMs > 0 }?.let { it.remainingMs.toFloat() / it.totalMs }
            ?: 0f,
    nowMs: Long = SLEEP_SHEET_NOW_MS,
): SleepSheetState =
    SleepSheetState(
        timer = timer,
        chapter = loadedPlayerState(isWholeBook = isWholeBook).chapter,
        chapterRemainingMs = { chapterRemainingMs },
        countdownMs = { countdownMs },
        meterFraction = { meterFraction },
        nowMs = { nowMs },
    )
