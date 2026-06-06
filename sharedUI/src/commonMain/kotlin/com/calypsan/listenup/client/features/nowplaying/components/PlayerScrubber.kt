package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.features.nowplaying.WavySeekBar
import com.calypsan.listenup.client.features.nowplaying.formatPlaybackTime
import kotlin.time.Duration.Companion.milliseconds

/**
 * Chapter-scoped scrubber combining [WavySeekBar] with elapsed and remaining time labels.
 *
 * The elapsed label shows [chapterPositionMs] formatted as a human-readable time string;
 * the remaining label shows the negative remaining time (e.g. "-15:05"). Both use tabular
 * figures so digits never shift horizontally during playback.
 *
 * @param chapterProgress Playhead position within the current chapter (0f–1f).
 * @param chapterPositionMs Elapsed chapter position in milliseconds.
 * @param chapterDurationMs Total chapter duration in milliseconds.
 * @param isPlaying Whether audio is currently playing; drives the wave amplitude animation.
 * @param isBuffering When true, seeking is disabled and the seek bar is non-interactive.
 * @param onSeek Called with the new fractional position when the user seeks.
 */
@Composable
fun PlayerScrubber(
    chapterProgress: Float,
    chapterPositionMs: Long,
    chapterDurationMs: Long,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val elapsedDuration = chapterPositionMs.milliseconds
    val remainingDuration = (chapterDurationMs - chapterPositionMs).coerceAtLeast(0L).milliseconds

    val elapsedLabel = elapsedDuration.formatPlaybackTime()
    val remainingLabel = "-${remainingDuration.formatPlaybackTime()}"

    // Tabular-nums so digits don't shift as time advances.
    val timeStyle =
        MaterialTheme.typography.labelMedium.copy(
            fontFeatureSettings = "tnum",
        )
    val timeColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        WavySeekBar(
            progress = chapterProgress,
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBuffering,
            isPlaying = isPlaying,
            stateDescription = "$elapsedLabel of ${chapterDurationMs.milliseconds.formatPlaybackTime()}",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = elapsedLabel, style = timeStyle, color = timeColor)
            Text(text = remainingLabel, style = timeStyle, color = timeColor)
        }
    }
}
