package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.features.nowplaying.components.wayOut
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_cover_a11y
import listenup.composeapp.generated.resources.player_pause
import listenup.composeapp.generated.resources.player_play
import org.jetbrains.compose.resources.stringResource

/**
 * Slim now-playing strip for the reader dock.
 *
 * Shows: small cover · chapter/title + time-left · filled play/pause button.
 * Renders only while a book is loaded ([PlayerUiState.Loaded]); no-ops otherwise. The button is the shared
 * primary: play/pause, or the way out of a failure (Retry, Sign in, …).
 *
 * Intentionally minimal — no transport skip, no speed pill, no expand button.
 * The reader dock pairs this above a [ReaderPageScrubber].
 *
 * @param progress reads the playback position. Only the time-left line calls it, so a position
 *   tick redraws that line and nothing else in the strip.
 */
@Composable
internal fun ReaderNowPlayingStrip(
    state: PlayerUiState,
    progress: () -> PlayerProgress,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    if (state !is PlayerUiState.Loaded) return
    val book = state.book

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
                bookId = book.id,
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                contentDescription = stringResource(Res.string.player_cover_a11y),
                title = book.title,
                author = book.authors.joinToString(", ") { it.name },
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.extraSmall),
            )

            Spacer(Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text =
                        state.chapter.title
                            .takeUnless { state.chapter.isWholeBook }
                            ?.takeIf { it.isNotBlank() } ?: book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TimeLeft(progress)
            }

            Spacer(Modifier.width(8.dp))

            IconButton(
                onClick = {
                    haptics.press()
                    onPrimary()
                },
            ) {
                val isPause = state.primary == PrimaryControl.Pause
                val way = state.primary.wayOut()
                Icon(
                    imageVector = way?.first ?: if (isPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription =
                        stringResource(
                            way?.second ?: if (isPause) Res.string.player_pause else Res.string.player_play,
                        ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The time left in the book: the only part of the strip that moves with the playhead. */
@Composable
private fun TimeLeft(progress: () -> PlayerProgress) {
    Text(
        text = DurationFormatter.timeLeft(progress().bookRemainingMs.milliseconds),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}
