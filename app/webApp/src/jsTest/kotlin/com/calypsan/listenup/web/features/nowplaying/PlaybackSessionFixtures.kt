package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.domain.model.LoadedBook
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerOverlay
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A session over a state that never changes: the shape specs pass in place of the graph, so a spec about
 * navigation or layout is not forced to wire up a decoder. [PlayerUiState.NothingLoaded] draws no bar.
 *
 * Deliberately NOT a default value on [com.calypsan.listenup.web.WebAppRoot]'s parameter. Its `onPlayBook`
 * is inert, and Book Detail's Play button is gated on the book's own `canPlay` rather than on whether
 * playback is wired, so a production call site that forgot the argument would render a real, dead Play
 * button and compile clean. Making every caller name its source turns that into a compile error.
 */
fun fixedPlayback(
    state: PlayerUiState = PlayerUiState.NothingLoaded,
    progress: PlayerProgress = PlayerProgress.Zero,
    preparingBookId: String? = null,
    boostUnavailable: Boolean = false,
    loadedBook: Flow<LoadedBook> = flowOf(state.asLoadedBook()),
): OpenPlayback =
    {
        PlaybackSession(
            state = MutableStateFlow(state),
            progress = MutableStateFlow(progress),
            loadedBook = loadedBook,
            overlay = MutableStateFlow(PlayerOverlay.None),
            preparingBookId = MutableStateFlow(preparingBookId),
            boostUnavailable = MutableStateFlow(boostUnavailable),
            events = emptyFlow(),
            onPrimary = {},
            onPlayPause = {},
            onPlayBook = { _, _ -> },
            onSeek = {},
            onSkipBack = {},
            onSkipForward = {},
            onPreviousChapter = {},
            onNextChapter = {},
            onSeekToChapter = {},
            onSetSpeed = {},
            onResetSpeed = {},
            onStepSpeed = {},
            onSetBoost = {},
            onResetBoost = {},
            onSetSleepTimer = {},
            onCancelSleepTimer = {},
            onExtendSleepTimer = {},
            onRetry = {},
            onDismissError = {},
            onCloseBook = {},
            onCardAction = {},
            onRate = {},
            close = {},
        )
    }

/** What a fixed [state] says about the loaded book, for [fixedPlayback]: a fixed state has nothing left to restore. */
private fun PlayerUiState.asLoadedBook(): LoadedBook =
    when (this) {
        PlayerUiState.NothingLoaded -> {
            LoadedBook.NothingLoaded
        }

        is PlayerUiState.Loaded -> {
            LoadedBook.Loaded(
                bookId = book.id,
                isPlaying = phase == PlayerPhase.Playing,
                isFinished = phase == PlayerPhase.Finished,
            )
        }
    }
