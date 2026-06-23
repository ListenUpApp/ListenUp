package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.features.nowplaying.components.Ctrl
import com.calypsan.listenup.client.features.nowplaying.components.PlayPauseFab
import com.calypsan.listenup.client.features.nowplaying.components.SpeedPill
import com.calypsan.listenup.client.features.settings.PlaybackSpeedPresets
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_cover_a11y
import listenup.composeapp.generated.resources.player_expand
import listenup.composeapp.generated.resources.player_skip_back_10s
import listenup.composeapp.generated.resources.player_skip_forward_30s
import org.jetbrains.compose.resources.stringResource

/** Height of the docked mini-player bar rendered on TV, Desktop, and Tablet. */
internal val DockedNowPlayingBarHeight = 96.dp

/**
 * Docked mini-player bar for TV, Desktop, and Tablet form factors.
 *
 * A full-width bar ([DockedNowPlayingBarHeight] tall, [surfaceContainerLow] background, large rounded corners)
 * with three flex regions:
 * - LEFT: 60dp cover + book title / chapter info
 * - CENTRE: transport controls (replay-10 / play-pause FAB / forward-30) above a
 *   seekable [WavySeekBar] with elapsed and remaining time labels
 * - RIGHT: speed pill + expand button
 *
 * Renders for [NowPlayingState.Active] only; animated in/out with slide + fade.
 */
@Composable
fun DockedNowPlayingBar(
    state: NowPlayingState,
    progress: PlaybackProgress,
    isExpanded: Boolean,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVisible = state is NowPlayingState.Active && !isExpanded

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        val focusScale by animateFloatAsState(
            targetValue = if (isFocused) 1.02f else 1f,
            label = "docked_player_focus_scale",
        )
        val focusBorderColor = MaterialTheme.colorScheme.primary
        val barShape = RoundedCornerShape(28.dp)

        Surface(
            onClick = onTap,
            interactionSource = interactionSource,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = focusScale
                        scaleY = focusScale
                    }.then(
                        if (isFocused) {
                            Modifier.border(
                                width = 2.dp,
                                color = focusBorderColor,
                                shape = barShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            shape = barShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 10.dp,
        ) {
            if (state is NowPlayingState.Active) {
                ActiveDockedContent(
                    state = state,
                    progress = progress,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onSeek = onSeek,
                    onSpeedClick = onSpeedClick,
                    onExpand = onTap,
                )
            }
        }
    }
}

@Composable
private fun ActiveDockedContent(
    state: NowPlayingState.Active,
    progress: PlaybackProgress,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeedClick: () -> Unit,
    onExpand: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(DockedNowPlayingBarHeight)
                .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LEFT: Cover + title / chapter line
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverImage(
                bookId = state.bookId,
                coverPath = state.coverPath,
                coverHash = state.coverHash,
                blurHash = state.coverBlurHash,
                contentDescription = stringResource(Res.string.player_cover_a11y),
                title = state.title,
                author = state.author,
                modifier =
                    Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp)),
            )

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val chapterLine =
                    if (state.chapterTitle != null) {
                        "Ch. ${state.chapterIndex + 1} · ${state.chapterTitle}"
                    } else {
                        "Ch. ${state.chapterIndex + 1}"
                    }
                Text(
                    text = chapterLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(20.dp))

        // CENTRE: Transport row above scrubber row
        Column(
            modifier = Modifier.weight(2f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Transport controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ctrl(
                    icon = Icons.Default.Replay10,
                    contentDescription = stringResource(Res.string.player_skip_back_10s),
                    onClick = onSkipBack,
                    size = 44.dp,
                )
                PlayPauseFab(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onClick = onPlayPause,
                    size = 52.dp,
                    shadowElevation = 0.dp,
                )
                Ctrl(
                    icon = Icons.Default.Forward30,
                    contentDescription = stringResource(Res.string.player_skip_forward_30s),
                    onClick = onSkipForward,
                    size = 44.dp,
                )
            }

            ChapterScrubberRow(
                chapterPositionMs = progress.chapterPositionMs,
                chapterDurationMs = progress.chapterDurationMs,
                chapterProgress = progress.chapterProgress,
                isPlaying = state.isPlaying,
                onSeek = onSeek,
            )
        }

        Spacer(Modifier.width(20.dp))

        // RIGHT: Speed pill + expand button
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpeedPill(
                label = PlaybackSpeedPresets.format(state.playbackSpeed),
                onClick = onSpeedClick,
            )
            Ctrl(
                icon = Icons.Default.OpenInFull,
                contentDescription = stringResource(Res.string.player_expand),
                onClick = onExpand,
                size = 44.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Elapsed label · [WavySeekBar] · -remaining label, filling available width. */
@Composable
private fun ChapterScrubberRow(
    chapterPositionMs: Long,
    chapterDurationMs: Long,
    chapterProgress: Float,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val elapsedTime = chapterPositionMs.milliseconds.formatPlaybackTime()
        val remainingMs = (chapterDurationMs - chapterPositionMs).coerceAtLeast(0)
        val remainingTime = "-${remainingMs.milliseconds.formatPlaybackTime()}"

        Text(
            text = elapsedTime,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WavySeekBar(
            progress = chapterProgress,
            onSeek = onSeek,
            modifier = Modifier.weight(1f),
            enabled = true,
            isPlaying = isPlaying,
        )
        Text(
            text = remainingTime,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
