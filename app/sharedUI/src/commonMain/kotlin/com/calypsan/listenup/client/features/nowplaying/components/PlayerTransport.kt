package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
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
import listenup.composeapp.generated.resources.player_skip_backward
import listenup.composeapp.generated.resources.player_skip_forward
import org.jetbrains.compose.resources.stringResource

/**
 * Full transport control row: previous chapter, skip back, play/pause FAB,
 * skip forward, next chapter.
 *
 * The play/pause FAB is a squircle-shaped [RoundedCornerShape](20.dp) button with
 * [MaterialTheme.colorScheme.primary] background and a wavy circular progress indicator
 * while [isBuffering] is true. Chapter-skip controls use [onSurfaceVariant] tint to
 * visually de-emphasise them relative to the skip controls.
 *
 * @param isPlaying Whether playback is active (determines play/pause icon).
 * @param isBuffering Whether the player is loading (shows spinner instead of icon).
 * @param onPlayPause Called when the play/pause FAB is tapped.
 * @param onSkipBack Called when the skip-back control is tapped.
 * @param onSkipForward Called when the skip-forward control is tapped.
 * @param onPreviousChapter Called when the skip-previous control is tapped.
 * @param onNextChapter Called when the skip-next control is tapped.
 * @param skipBackwardSec The configured backward skip, in seconds — drives the icon and label.
 * @param skipForwardSec The configured forward skip, in seconds — drives the icon and label.
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
    skipBackwardSec: Int,
    skipForwardSec: Int,
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
            icon = SkipGlyphs.backward(skipBackwardSec),
            contentDescription = stringResource(Res.string.player_skip_backward, skipBackwardSec),
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
            icon = SkipGlyphs.forward(skipForwardSec),
            contentDescription = stringResource(Res.string.player_skip_forward, skipForwardSec),
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
