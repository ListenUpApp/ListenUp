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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import com.calypsan.listenup.client.playback.NowPlayingState

/**
 * Floating mini player that appears above bottom navigation.
 *
 * M3 Expressive styling:
 * - Full pill shape (28dp corners)
 * - Diverse button sizes (play larger than skips)
 * - Pill-shaped skip buttons (horizontally elongated)
 * - Tonal elevation for depth
 *
 * Renders for [NowPlayingState.Active] and [NowPlayingState.Preparing] only;
 * hidden on Idle/Error.
 */
@Composable
fun NowPlayingBar(
    state: NowPlayingState,
    isExpanded: Boolean,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVisible =
        when (state) {
            is NowPlayingState.Active -> !isExpanded
            is NowPlayingState.Preparing -> !isExpanded
            else -> false
        }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        val focusScale by animateFloatAsState(
            targetValue = if (isFocused) 1.05f else 1f,
            label = "mini_player_focus_scale",
        )
        val focusBorderColor = MaterialTheme.colorScheme.primary
        val focusBorderShape = MaterialTheme.shapes.large

        Surface(
            onClick = onTap,
            interactionSource = interactionSource,
            modifier =
                Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .height(80.dp)
                    .graphicsLayer {
                        scaleX = focusScale
                        scaleY = focusScale
                    }.then(
                        if (isFocused) {
                            Modifier.border(
                                width = 2.dp,
                                color = focusBorderColor,
                                shape = focusBorderShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
        ) {
            when (state) {
                is NowPlayingState.Active -> {
                    ActiveContent(
                        state = state,
                        focusBorderColor = focusBorderColor,
                        onPlayPause = onPlayPause,
                        onSkipBack = onSkipBack,
                        onSkipForward = onSkipForward,
                    )
                }

                is NowPlayingState.Preparing -> {
                    PreparingContent(state = state, focusBorderColor = focusBorderColor)
                }

                else -> { /* Idle/Error: not visible */ }
            }
        }
    }
}

@Composable
private fun ActiveContent(
    state: NowPlayingState.Active,
    focusBorderColor: androidx.compose.ui.graphics.Color,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverImage(
                bookId = state.bookId,
                coverPath = state.coverPath,
                blurHash = state.coverBlurHash,
                contentDescription = "Book cover",
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.chapterTitle?.let { chapter ->
                    Text(
                        text = chapter,
                        style = MaterialTheme.typography.labelSmall,
                        color = focusBorderColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            NowPlayingBarControls(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
            )
        }

        LinearProgressIndicator(
            progress = { state.bookProgress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun PreparingContent(
    state: NowPlayingState.Preparing,
    focusBorderColor: androidx.compose.ui.graphics.Color,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverImage(
                bookId = state.bookId,
                coverPath = state.coverPath,
                blurHash = state.coverBlurHash,
                contentDescription = "Book cover",
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifEmpty { "Preparing..." },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.message ?: "Preparing audio...",
                    style = MaterialTheme.typography.bodySmall,
                    color = focusBorderColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        LinearProgressIndicator(
            progress = { state.progress / 100f },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            color = MaterialTheme.colorScheme.tertiary,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun NowPlayingBarControls(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Skip back - rounded square, tonal
        FilledTonalIconButton(
            onClick = onSkipBack,
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                Icons.Default.Replay10,
                contentDescription = "Skip back 10 seconds",
                modifier = Modifier.size(20.dp),
            )
        }

        // Play/Pause - larger, filled, rounded square (hero button)
        // Shows a spinner during mid-playback buffering.
        FilledIconButton(
            onClick = onPlayPause,
            enabled = !isBuffering,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        // Skip forward - rounded square, tonal
        FilledTonalIconButton(
            onClick = onSkipForward,
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(
                Icons.Default.Forward30,
                contentDescription = "Skip forward 30 seconds",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
