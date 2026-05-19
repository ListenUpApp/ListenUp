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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.playback.NowPlayingState

/**
 * Docked mini player bar for TV and desktop form factors.
 *
 * Full-width, flush with screen edges, Spotify-desktop style.
 * Progress bar at top, transport controls centered, chapter info on the right.
 *
 * Renders for [NowPlayingState.Active] and [NowPlayingState.Preparing] only;
 * hidden on Idle/Error.
 */
@Composable
fun DockedNowPlayingBar(
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
            targetValue = if (isFocused) 1.02f else 1f,
            label = "docked_player_focus_scale",
        )
        val focusBorderColor = MaterialTheme.colorScheme.primary

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
                                shape = RectangleShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            shape = RectangleShape,
            tonalElevation = 3.dp,
        ) {
            when (state) {
                is NowPlayingState.Active -> {
                    ActiveDockedContent(
                        state = state,
                        onPlayPause = onPlayPause,
                        onSkipBack = onSkipBack,
                        onSkipForward = onSkipForward,
                    )
                }

                is NowPlayingState.Preparing -> {
                    PreparingDockedContent(state = state)
                }

                else -> { /* Idle/Error: not visible */ }
            }
        }
    }
}

@Composable
private fun ActiveDockedContent(
    state: NowPlayingState.Active,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    Column {
        // Progress bar at the top
        LinearProgressIndicator(
            progress = { state.bookProgress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            trackColor = Color.Transparent,
            drawStopIndicator = {},
        )

        // Main content row — three sections with controls centered
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Cover + title/author
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCoverImage(
                    bookId = state.bookId,
                    coverPath = state.coverPath,
                    blurHash = state.coverBlurHash,
                    contentDescription = "Book cover",
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp)),
                )

                Spacer(Modifier.width(16.dp))

                Column {
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
                }
            }

            // Center: Transport controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(
                    onClick = onSkipBack,
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Default.Replay10,
                        contentDescription = "Skip back 10 seconds",
                        modifier = Modifier.size(22.dp),
                    )
                }

                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                    )
                }

                FilledTonalIconButton(
                    onClick = onSkipForward,
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Default.Forward30,
                        contentDescription = "Skip forward 30 seconds",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Right: Chapter + time remaining
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                ) {
                    state.chapterTitle?.let { chapter ->
                        Text(
                            text = chapter,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    }
                    val remainingMs = state.bookDurationMs - state.bookPositionMs
                    if (remainingMs > 0) {
                        Text(
                            text = formatTimeRemaining(remainingMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreparingDockedContent(state: NowPlayingState.Preparing) {
    Column {
        // Progress bar at the top
        LinearProgressIndicator(
            progress = { state.progress / 100f },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            trackColor = Color.Transparent,
            color = MaterialTheme.colorScheme.tertiary,
            drawStopIndicator = {},
        )

        // Cover + title/message — no transport controls or chapter info during preparing
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCoverImage(
                    bookId = state.bookId,
                    coverPath = state.coverPath,
                    blurHash = state.coverBlurHash,
                    contentDescription = "Book cover",
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp)),
                )

                Spacer(Modifier.width(16.dp))

                Column {
                    Text(
                        text = state.title.ifEmpty { "Preparing..." },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.message ?: "Preparing audio...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatTimeRemaining(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m left"
        else -> "${minutes}m left"
    }
}
