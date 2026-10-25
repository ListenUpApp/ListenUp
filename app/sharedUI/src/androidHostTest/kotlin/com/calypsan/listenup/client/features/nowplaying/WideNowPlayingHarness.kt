package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.player.PlayerCardAction
import com.calypsan.listenup.client.presentation.player.PlayerMenuItem
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState

/** [WideNowPlaying] for the wide screen's tests: every callback the test doesn't name does nothing. */
@Suppress("LongParameterList") // one optional hook per callback a wide test observes
@Composable
internal fun TestWideNowPlaying(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress = { samplePlayerProgress },
    onMenuItem: (PlayerMenuItem) -> Unit = {},
    onChaptersClick: () -> Unit = {},
    onSeekToChapter: (Int) -> Unit = {},
    onCardAction: (PlayerCardAction) -> Unit = {},
    onChapterPanelShown: (Boolean) -> Unit = {},
) {
    WideNowPlaying(
        state = state,
        progress = progress,
        onCollapse = {},
        onPrimary = {},
        onSeek = {},
        onSkipBack = {},
        onSkipForward = {},
        onPreviousChapter = {},
        onNextChapter = {},
        onSpeedClick = {},
        onSleepClick = {},
        onChaptersClick = onChaptersClick,
        onSeekToChapter = onSeekToChapter,
        onGoToContributor = {},
        onShowAuthorPicker = {},
        onShowNarratorPicker = {},
        onMenuItem = onMenuItem,
        onCardAction = onCardAction,
        onRate = {},
        onChapterPanelShown = onChapterPanelShown,
    )
}

/** The fixture book with no chapters: one whole-book chapter and an empty list (§3.2). */
internal fun wholeBookState(): PlayerUiState.Loaded =
    loadedPlayerState(coverPath = NO_COVER, isWholeBook = true).copy(chapters = emptyList())
