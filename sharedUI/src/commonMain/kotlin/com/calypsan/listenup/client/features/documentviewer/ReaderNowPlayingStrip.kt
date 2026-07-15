package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.calypsan.listenup.client.playback.NowPlayingState
import com.calypsan.listenup.client.playback.PlaybackProgress
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_cover_a11y
import listenup.composeapp.generated.resources.player_pause
import listenup.composeapp.generated.resources.player_play
import org.jetbrains.compose.resources.stringResource

/**
 * Slim now-playing strip for the reader dock.
 *
 * Shows: small cover · chapter/title + time-left · filled play/pause button.
 * Renders only for [NowPlayingState.Active]; no-ops otherwise.
 *
 * Intentionally minimal — no transport skip, no speed pill, no expand button.
 * The reader dock pairs this above a [ReaderPageScrubber].
 */
@Composable
internal fun ReaderNowPlayingStrip(
    state: NowPlayingState,
    progress: PlaybackProgress,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state !is NowPlayingState.Active) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCoverImage(
                bookId = state.bookId,
                coverPath = state.coverPath,
                coverHash = state.coverHash,
                contentDescription = stringResource(Res.string.player_cover_a11y),
                title = state.title,
                author = state.author,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(6.dp)),
            )

            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = state.chapterTitle ?: state.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val remainingMs = (progress.bookDurationMs - progress.bookPositionMs).coerceAtLeast(0L)
                Text(
                    text = formatTimeLeft(remainingMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription =
                        if (state.isPlaying) {
                            stringResource(Res.string.player_pause)
                        } else {
                            stringResource(Res.string.player_play)
                        },
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
