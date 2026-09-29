package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState

/**
 * The [TransportBar], collecting its own flows from [playback].
 *
 * ⛔ A composable of its own so the playback tick stops at the bar. [PlaybackSession.state] carries
 * the position, and is rebuilt several times a second while a book plays; collected in the shell's
 * content lambda, every one of those ticks re-ran that lambda — the account menu, the route switch,
 * the dead-letter notice, the palette host — to move one scrubber thumb. Collected here, a tick
 * recomposes this scope and nothing above it.
 */
@Composable
fun TransportBarHost(
    playback: PlaybackSession,
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenContributor: (String) -> Unit,
) {
    TransportBar(
        state = playback.state.collectAsState().value,
        onPlayPause = playback.onPlayPause,
        onSeek = playback.onSeek,
        onSkipBack = playback.onSkipBack,
        onSkipForward = playback.onSkipForward,
        onSetSpeed = playback.onSetSpeed,
        onResetSpeed = playback.onResetSpeed,
        defaultSpeed = playback.defaultSpeed.collectAsState().value,
        chapters = playback.chapters.collectAsState().value,
        currentChapterIndex = playback.currentChapterIndex.collectAsState().value,
        onSeekToChapter = playback.onSeekToChapter,
        sleepTimer = playback.sleepTimer.collectAsState().value,
        onSetSleepTimer = playback.onSetSleepTimer,
        onCancelSleepTimer = playback.onCancelSleepTimer,
        onExtendSleepTimer = playback.onExtendSleepTimer,
        volumeBoostDb = playback.volumeBoostDb.collectAsState().value,
        defaultBoostDb = playback.defaultBoostDb.collectAsState().value,
        boostUnavailable = playback.boostUnavailable.collectAsState().value,
        onSetBoost = playback.onSetBoost,
        onResetBoost = playback.onResetBoost,
        nowPlaying = playback.nowPlaying.collectAsState().value,
        onOpenBook = onOpenBook,
        onOpenSeries = onOpenSeries,
        onOpenContributor = onOpenContributor,
    )
}
