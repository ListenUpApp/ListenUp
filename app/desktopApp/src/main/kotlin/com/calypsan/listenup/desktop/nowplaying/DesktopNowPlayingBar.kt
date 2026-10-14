package com.calypsan.listenup.desktop.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState

/**
 * Mini player bar shown at the bottom of the desktop window during playback.
 *
 * Renders full controls for the loaded book, or an error band when its phase is
 * [PlayerPhase.Failed] or [PlayerPhase.Unavailable]. The caller renders nothing when no book
 * is loaded.
 *
 * Clicking the bar (outside of controls) expands to the full now-playing screen.
 */
@Composable
fun DesktopNowPlayingBar(
    state: PlayerUiState.Loaded,
    progress: () -> PlayerProgress,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column {
            // Progress indicator at top of bar
            LinearProgressIndicator(
                progress = { progress().bookFraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClick)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val problem =
                    when (val phase = state.phase) {
                        is PlayerPhase.Failed -> phase.error
                        is PlayerPhase.Unavailable -> phase.reason
                        else -> null
                    }
                val isPlaying = state.phase == PlayerPhase.Playing || state.phase == PlayerPhase.SleepFading
                if (problem == null) {
                    BarCover(
                        bookId = state.book.id,
                        coverPath = state.book.coverPath,
                        contentDescription = state.book.title,
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.book.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text =
                                state.chapter.title.takeUnless { state.chapter.isWholeBook }
                                    ?: state.book.authors.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Playback controls
                    IconButton(onClick = onSkipBack, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Skip back",
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    IconButton(onClick = onPlayPause, modifier = Modifier.size(44.dp)) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    IconButton(onClick = onSkipForward, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Skip forward",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.book.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = problem.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BarCover(
    bookId: String,
    coverPath: String?,
    contentDescription: String,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        BookCoverImage(
            bookId = bookId,
            coverPath = coverPath,
            contentDescription = contentDescription,
            modifier = Modifier.size(48.dp),
        )
    }
}
