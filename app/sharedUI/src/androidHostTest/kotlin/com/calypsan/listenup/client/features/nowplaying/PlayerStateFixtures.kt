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

/** The fixture book's length, 45h 30m, which its [bookChapterRows] sum to. */
internal const val FIXTURE_BOOK_MS = 163_800_000L

/**
 * 75 chapter rows that sum to the fixture book: chapter 6 is "Bridge Four" (20:35), chapter 7 is "Anything Reasonable"
 * (33:18), and [current] (a zero-based index) is the one playing.
 */
internal fun bookChapterRows(current: Int): List<PlayerChapterRow> {
    val named = mapOf(5 to ("Bridge Four" to 1_235_000L), 6 to ("Anything Reasonable" to 1_998_000L))
    val rest = (FIXTURE_BOOK_MS - named.values.sumOf { it.second }) / 73
    val durations = (0 until 75).map { named[it]?.second ?: rest }.toMutableList()
    durations[74] += FIXTURE_BOOK_MS - durations.sum()
    var start = 0L
    return (0 until 75).map { index ->
        PlayerChapterRow(
            index = index,
            number = index + 1,
            title = named[index]?.first ?: "Chapter ${index + 1}",
            startMs = start,
            durationMs = durations[index],
            isCurrent = index == current,
        ).also { start += durations[index] }
    }
}

/**
 * The fixture book playing chapter [current] (zero-based) of its 75 [bookChapterRows], for the wide screen's tests.
 * Defaults to chapter 6, "Bridge Four", in "The Stormlight Archive" as book 1, with a cover that needs no Koin.
 */
internal fun chapteredState(
    current: Int = 5,
    series: List<PlayerSeriesRef> = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1")),
    phase: PlayerPhase = PlayerPhase.Playing,
    primary: PrimaryControl = PrimaryControl.Pause,
    source: AudioSource = AudioSource.Downloaded,
): PlayerUiState.Loaded {
    val rows = bookChapterRows(current)
    val row = rows[current]
    val base = loadedPlayerState(coverPath = NO_COVER, series = series, phase = phase, primary = primary, source = source)
    return base.copy(
        chapter =
            base.chapter.copy(
                index = row.index,
                number = row.number,
                title = row.title,
                startMs = row.startMs,
                durationMs = row.durationMs,
            ),
        chapters = rows,
    )
}
