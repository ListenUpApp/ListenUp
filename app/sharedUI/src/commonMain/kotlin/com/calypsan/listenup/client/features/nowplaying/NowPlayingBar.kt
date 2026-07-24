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
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import com.calypsan.listenup.client.features.nowplaying.components.Ctrl
import com.calypsan.listenup.client.features.nowplaying.components.PlayPauseFab
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_cover_a11y
import listenup.composeapp.generated.resources.player_skip_back_10s
import org.jetbrains.compose.resources.stringResource

/**
 * Floating mini-player card docked above the bottom navigation.
 *
 * M3 Expressive styling per the MiniPlayerMobile design:
 * - [surfaceContainerHigh] card with large rounded corners
 * - Cover thumbnail · title + chapter line · skip-back · play/pause squircle
 * - Non-interactive wavy progress strip along the bottom (chapter progress)
 *
 * Visible only when [state] is [NowPlayingState.Active] and [isExpanded] is false.
 * Tapping anywhere expands to the full-screen player via [onTap].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NowPlayingBar(
    state: NowPlayingState,
    progress: () -> PlaybackProgress,
    isExpanded: Boolean,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
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
            targetValue = if (isFocused) 1.05f else 1f,
            label = "mini_player_focus_scale",
        )
        val focusBorderShape = MaterialTheme.shapes.large

        Surface(
            onClick = onTap,
            interactionSource = interactionSource,
            modifier =
                Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = focusScale
                        scaleY = focusScale
                    }.then(
                        if (isFocused) {
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = focusBorderShape,
                            )
                        } else {
                            Modifier
                        },
                    ),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
        ) {
            if (state is NowPlayingState.Active) {
                MiniPlayerContent(
                    state = state,
                    progress = progress,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniPlayerContent(
    state: NowPlayingState.Active,
    progress: () -> PlaybackProgress,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Cover thumbnail — 48dp, rounded 12dp corners
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
                        .clip(RoundedCornerShape(12.dp)),
            )

            Spacer(Modifier.width(4.dp))

            // Title + chapter info column — grows to fill available space
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
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

            // Skip-back control (single skip on the phone mini-player)
            Ctrl(
                icon = Icons.Default.Replay10,
                contentDescription = stringResource(Res.string.player_skip_back_10s),
                onClick = onSkipBack,
                size = 40.dp,
                tint = MaterialTheme.colorScheme.onSurface,
            )

            // Play/pause squircle FAB. No drop shadow — the bar's rounded Surface would clip it.
            PlayPauseFab(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onClick = onPlayPause,
                size = 48.dp,
                shadowElevation = 0.dp,
            )
        }

        // Thin, non-interactive chapter progress strip. A plain bar (not the wavy indicator) reads
        // cleaner at the mini-player's small height — the expressive wave is reserved for the
        // full-screen player and the desktop bar, where it has the vertical room to register.
        LinearProgressIndicator(
            progress = { progress().chapterProgress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
    }
}
