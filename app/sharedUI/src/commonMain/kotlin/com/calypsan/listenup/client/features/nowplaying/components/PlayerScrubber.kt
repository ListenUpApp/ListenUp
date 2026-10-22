package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.design.components.PlaybackSlider
import com.calypsan.listenup.client.presentation.player.PlayerProgress

/**
 * Now Playing's chapter-scoped scrubber: [PlaybackSlider] over elapsed · [center] · −remaining.
 *
 * [progress] is a deferred lambda so the 4 Hz position tick recomposes only this leaf, never the player chrome
 * around it (cover, title, transport), which reads nothing from it.
 *
 * @param progress Deferred read of the fast-changing playback progress.
 * @param isBusy When true (a stall, or a problem card), the scrubber holds still and takes no seek.
 * @param onSeek Called once, on release, with the chapter fraction to seek to.
 * @param center Drawn between the times: the book sentence on Now Playing.
 */
@Composable
fun PlayerScrubber(
    progress: () -> PlayerProgress,
    isBusy: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    center: (@Composable () -> Unit)? = null,
) {
    val p = progress()
    PlaybackSlider(
        fraction = p.chapterFraction,
        elapsedMs = p.chapterPositionMs,
        durationMs = p.chapterDurationMs,
        onSeek = onSeek,
        enabled = !isBusy,
        center = center,
        modifier = modifier,
    )
}
