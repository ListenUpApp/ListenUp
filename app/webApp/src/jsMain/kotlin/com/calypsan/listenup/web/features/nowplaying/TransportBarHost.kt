package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.calypsan.listenup.client.presentation.player.PlayerEvent
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import org.w3c.dom.HTMLElement

/**
 * The [TransportBar], collecting its own flows from [playback], and carrying out the navigation the
 * main button asks for (Go to book, the Inbox).
 *
 * ⛔ A composable of its own so the playback tick stops at the bar. [PlaybackSession.progress] moves
 * several times a second while a book plays; collected in the shell's content lambda, every one of those
 * ticks re-ran that lambda — the account menu, the route switch, the dead-letter notice, the palette host —
 * to move one scrubber thumb. Collected here, a tick recomposes this scope and nothing above it.
 *
 * [PlayerEvent.OpenSignIn] needs nothing here: a lapsed session already shows the shell's sign-in banner.
 * [PlayerEvent.OpenDocumentViewer] never arrives on web, which opens documents from Book Detail.
 */
@Composable
fun TransportBarHost(
    playback: PlaybackSession,
    onOpenBook: (String) -> Unit,
    onOpenInbox: () -> Unit = {},
    isNowPlayingOpen: Boolean = false,
    onOpenNowPlaying: (opener: HTMLElement?) -> Unit = {},
    onCloseNowPlaying: () -> Unit = {},
    onFocusChapters: (() -> Unit)? = null,
) {
    val openBook by rememberUpdatedState(onOpenBook)
    val openInbox by rememberUpdatedState(onOpenInbox)
    LaunchedEffect(playback) {
        playback.events.collect { event ->
            when (event) {
                is PlayerEvent.NavigateToBook -> openBook(event.bookId)
                PlayerEvent.OpenInbox -> openInbox()
                PlayerEvent.OpenSignIn, is PlayerEvent.OpenDocumentViewer -> Unit
            }
        }
    }

    val state = playback.state.collectAsState().value as? PlayerUiState.Loaded ?: return
    TransportBar(
        state = state,
        progress = playback.progress.collectAsState().value,
        onPrimary = playback.onPrimary,
        onSeek = playback.onSeek,
        onSkipBack = playback.onSkipBack,
        onSkipForward = playback.onSkipForward,
        onSetSpeed = playback.onSetSpeed,
        onResetSpeed = playback.onResetSpeed,
        onStepSpeed = playback.onStepSpeed,
        onSeekToChapter = playback.onSeekToChapter,
        onSetSleepTimer = playback.onSetSleepTimer,
        onCancelSleepTimer = playback.onCancelSleepTimer,
        onExtendSleepTimer = playback.onExtendSleepTimer,
        boostUnavailable = playback.boostUnavailable.collectAsState().value,
        onSetBoost = playback.onSetBoost,
        onResetBoost = playback.onResetBoost,
        isNowPlayingOpen = isNowPlayingOpen,
        onOpenNowPlaying = onOpenNowPlaying,
        onCloseNowPlaying = onCloseNowPlaying,
        onFocusChapters = onFocusChapters,
    )
}
