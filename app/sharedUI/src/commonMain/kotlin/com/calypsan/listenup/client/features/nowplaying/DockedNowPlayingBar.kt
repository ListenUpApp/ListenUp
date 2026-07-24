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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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

/** Height of the single-row docked mini-player bar (medium/expanded width, TV). */
internal val DockedNowPlayingBarHeight = 72.dp

/**
 * Docked mini-player bar for medium/expanded widths (tablet, unfolded foldable, desktop) and TV.
 *
 * A full-width single-row bar ([DockedNowPlayingBarHeight] tall, [surfaceContainerLow] background,
 * large rounded corners), everything on one line, vertically centred:
 * - 48dp cover + book title / chapter info (bounded so the scrubber gets the flexible width)
 * - transport controls: replay-10 / play-pause FAB / forward-30
 * - inline seekable [WavySeekBar] with elapsed and remaining time labels (fills remaining width)
 * - speed pill + expand button
 *
 * Renders for [NowPlayingState.Active] only; animated in/out with slide + fade.
 */
@Composable
fun DockedNowPlayingBar(
    state: NowPlayingState,
    progress: () -> PlaybackProgress,
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
    progress: () -> PlaybackProgress,
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
                .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Cover + title / chapter — bounded so the inline scrubber gets the flexible width.
        BookCoverImage(
            bookId = state.bookId,
            coverPath = state.coverPath,
            coverHash = state.coverHash,
            contentDescription = stringResource(Res.string.player_cover_a11y),
            title = state.title,
            author = state.author,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.widthIn(max = 200.dp)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Show the chapter's own title; no "Ch. N · " prefix — the title is often itself
            // "Chapter N", and chapterIndex counts front-matter tracks (Introduction, Epigraph),
            // so the prefix produced confusing, offset labels like "Ch. 3 · Chapter 1".
            val chapterLine =
                state.chapterTitle?.takeIf { it.isNotBlank() }
                    ?: "Chapter ${state.chapterIndex + 1}"
            Text(
                text = chapterLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Transport.
        Ctrl(
            icon = Icons.Default.Replay10,
            contentDescription = stringResource(Res.string.player_skip_back_10s),
            onClick = onSkipBack,
            size = 40.dp,
        )
        PlayPauseFab(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            onClick = onPlayPause,
            size = 44.dp,
            shadowElevation = 0.dp,
        )
        Ctrl(
            icon = Icons.Default.Forward30,
            contentDescription = stringResource(Res.string.player_skip_forward_30s),
            onClick = onSkipForward,
            size = 40.dp,
        )

        // Inline scrubber — fills the remaining width. It reads [progress] internally so a
        // position tick recomposes only the scrubber row, not this whole docked bar.
        ChapterScrubberRow(
            progress = progress,
            isPlaying = state.isPlaying,
            onSeek = onSeek,
            modifier = Modifier.weight(1f),
        )

        // Speed + expand.
        SpeedPill(
            label = PlaybackSpeedPresets.format(state.playbackSpeed),
            onClick = onSpeedClick,
        )
        Ctrl(
            icon = Icons.Default.OpenInFull,
            contentDescription = stringResource(Res.string.player_expand),
            onClick = onExpand,
            size = 40.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Elapsed label · [WavySeekBar] · -remaining label, filling available width. */
@Composable
private fun ChapterScrubberRow(
    progress: () -> PlaybackProgress,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val current = progress()
        val elapsedTime = current.chapterPositionMs.milliseconds.formatPlaybackTime()
        val remainingMs = (current.chapterDurationMs - current.chapterPositionMs).coerceAtLeast(0)
        val remainingTime = "-${remainingMs.milliseconds.formatPlaybackTime()}"

        Text(
            text = elapsedTime,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WavySeekBar(
            progress = current.chapterProgress,
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
