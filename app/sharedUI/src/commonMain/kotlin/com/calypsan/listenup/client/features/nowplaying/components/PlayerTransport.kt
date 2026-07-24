package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_next_chapter
import listenup.composeapp.generated.resources.player_previous_chapter
import listenup.composeapp.generated.resources.player_skip_back_10s
import listenup.composeapp.generated.resources.player_skip_forward_30s
import org.jetbrains.compose.resources.stringResource

/**
 * Full transport control row: previous chapter, skip back 10s, play/pause FAB,
 * skip forward 30s, next chapter.
 *
 * The play/pause FAB is a squircle-shaped [RoundedCornerShape](20.dp) button with
 * [MaterialTheme.colorScheme.primary] background and a wavy circular progress indicator
 * while [isBuffering] is true. Chapter-skip controls use [onSurfaceVariant] tint to
 * visually de-emphasise them relative to the skip-10/30 controls.
 *
 * @param isPlaying Whether playback is active (determines play/pause icon).
 * @param isBuffering Whether the player is loading (shows spinner instead of icon).
 * @param onPlayPause Called when the play/pause FAB is tapped.
 * @param onSkipBack Called when the replay-10 control is tapped.
 * @param onSkipForward Called when the forward-30 control is tapped.
 * @param onPreviousChapter Called when the skip-previous control is tapped.
 * @param onNextChapter Called when the skip-next control is tapped.
 * @param fabSize Diameter of the central play/pause FAB (default 96.dp).
 * @param ctrlSize Diameter of the secondary icon-button controls (default 60.dp).
 */
@Composable
fun PlayerTransport(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
    fabSize: Dp = 96.dp,
    ctrlSize: Dp = 60.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ctrl(
            icon = Icons.Default.SkipPrevious,
            contentDescription = stringResource(Res.string.player_previous_chapter),
            onClick = onPreviousChapter,
            size = ctrlSize,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Ctrl(
            icon = Icons.Default.Replay10,
            contentDescription = stringResource(Res.string.player_skip_back_10s),
            onClick = onSkipBack,
            size = ctrlSize,
            tint = MaterialTheme.colorScheme.onSurface,
        )

        PlayPauseFab(
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            onClick = onPlayPause,
            size = fabSize,
        )

        Ctrl(
            icon = Icons.Default.Forward30,
            contentDescription = stringResource(Res.string.player_skip_forward_30s),
            onClick = onSkipForward,
            size = ctrlSize,
            tint = MaterialTheme.colorScheme.onSurface,
        )

        Ctrl(
            icon = Icons.Default.SkipNext,
            contentDescription = stringResource(Res.string.player_next_chapter),
            onClick = onNextChapter,
            size = ctrlSize,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
