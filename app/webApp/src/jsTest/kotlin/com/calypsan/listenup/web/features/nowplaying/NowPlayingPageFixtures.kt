package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.presentation.player.FinishedInfo
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import org.w3c.dom.HTMLElement

/** Builds [NowPlayingActions] with no-op defaults for specs. */
@Suppress("LongParameterList")
internal fun nowPlayingActions(
    onCollapse: () -> Unit = {},
    onOpenBook: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    onOpenContributor: (String) -> Unit = {},
    onPrimary: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    onPreviousChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    onSeekToChapter: (Int) -> Unit = {},
    onCardAction: (PlayerCardAction) -> Unit = {},
    onRate: (Int) -> Unit = {},
    onCloseBook: () -> Unit = {},
    onCloseBookAndLeave: () -> Unit = {},
    onSetSpeed: (Float) -> Unit = {},
    onResetSpeed: () -> Unit = {},
    onStepSpeed: (Boolean) -> Unit = {},
    onSetSleepTimer: (SleepTimerMode) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onExtendSleepTimer: (Int) -> Unit = {},
    onSetBoost: (Float) -> Unit = {},
    onResetBoost: () -> Unit = {},
): NowPlayingActions =
    NowPlayingActions(
        onCollapse = onCollapse,
        onOpenBook = onOpenBook,
        onOpenSeries = onOpenSeries,
        onOpenContributor = onOpenContributor,
        onPrimary = onPrimary,
        onSeek = onSeek,
        onSkipBack = onSkipBack,
        onSkipForward = onSkipForward,
        onPreviousChapter = onPreviousChapter,
        onNextChapter = onNextChapter,
        onSeekToChapter = onSeekToChapter,
        onCardAction = onCardAction,
        onRate = onRate,
        onCloseBook = onCloseBook,
        onCloseBookAndLeave = onCloseBookAndLeave,
        onSetSpeed = onSetSpeed,
        onResetSpeed = onResetSpeed,
        onStepSpeed = onStepSpeed,
        onSetSleepTimer = onSetSleepTimer,
        onCancelSleepTimer = onCancelSleepTimer,
        onExtendSleepTimer = onExtendSleepTimer,
        onSetBoost = onSetBoost,
        onResetBoost = onResetBoost,
    )

/** Renders [TransportBar] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testTransportBar(
    state: PlayerUiState.Loaded,
    progress: PlayerProgress,
    onPrimary: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    onSetSpeed: (Float) -> Unit = {},
    onResetSpeed: () -> Unit = {},
    onStepSpeed: (Boolean) -> Unit = {},
    onSeekToChapter: (Int) -> Unit = {},
    onSetSleepTimer: (SleepTimerMode) -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    onExtendSleepTimer: (Int) -> Unit = {},
    boostUnavailable: Boolean = false,
    onSetBoost: (Float) -> Unit = {},
    onResetBoost: () -> Unit = {},
    isNowPlayingOpen: Boolean = false,
    onOpenNowPlaying: (opener: HTMLElement?) -> Unit = {},
    onCloseNowPlaying: () -> Unit = {},
    onFocusChapters: (() -> Unit)? = null,
) {
    TransportBar(
        state = state,
        progress = progress,
        onPrimary = onPrimary,
        onSeek = onSeek,
        onSkipBack = onSkipBack,
        onSkipForward = onSkipForward,
        onSetSpeed = onSetSpeed,
        onResetSpeed = onResetSpeed,
        onStepSpeed = onStepSpeed,
        onSeekToChapter = onSeekToChapter,
        onSetSleepTimer = onSetSleepTimer,
        onCancelSleepTimer = onCancelSleepTimer,
        onExtendSleepTimer = onExtendSleepTimer,
        boostUnavailable = boostUnavailable,
        onSetBoost = onSetBoost,
        onResetBoost = onResetBoost,
        isNowPlayingOpen = isNowPlayingOpen,
        onOpenNowPlaying = onOpenNowPlaying,
        onCloseNowPlaying = onCloseNowPlaying,
        onFocusChapters = onFocusChapters,
    )
}

/** The Way of Kings' length: 45h 30m. */
internal const val KINGS_MS = 163_800_000L

/** The Way of Kings as the shared model hands it to the Now Playing page: a known author, one series. */
@Suppress("LongParameterList") // one knob per thing a spec varies
internal fun wayOfKings(
    bookId: String = "b1",
    coverHash: String? = "abc123",
    authors: List<PlayerContributor> = listOf(PlayerContributor("c1", "Brandon Sanderson", 1)),
    narrators: List<PlayerContributor> = listOf(PlayerContributor("n1", "Michael Kramer", 1)),
    series: List<PlayerSeriesRef> = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1")),
    phase: PlayerPhase = PlayerPhase.Playing,
    primary: PrimaryControl? = null,
    chapters: List<PlayerChapterRow> = emptyList(),
    currentChapterIndex: Int? = null,
    boostDb: Float = 0f,
    firstPdfDocId: String? = null,
    finished: FinishedInfo? = null,
    genericTitles: Boolean = false,
): PlayerUiState.Loaded {
    val base =
        loadedPlayerState(
            title = "The Way of Kings",
            bookId = bookId,
            phase = phase,
            authors = authors,
            narrators = narrators,
            series = series,
            coverHash = coverHash,
            durationMs = KINGS_MS,
            chapters = chapters,
            currentChapterIndex = currentChapterIndex,
            boostDb = boostDb,
            firstPdfDocId = firstPdfDocId,
            finished = finished,
            genericTitles = genericTitles,
        )
    return primary?.let { base.copy(primary = it) } ?: base
}
