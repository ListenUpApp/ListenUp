package com.calypsan.listenup.client.features.documentviewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.MinTouchTarget
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.features.nowplaying.MiniPlayerGlyphSize
import com.calypsan.listenup.client.features.nowplaying.authorLine
import com.calypsan.listenup.client.features.nowplaying.components.Ctrl
import com.calypsan.listenup.client.features.nowplaying.components.MiniLineFit
import com.calypsan.listenup.client.features.nowplaying.components.MiniPlayerLineText
import com.calypsan.listenup.client.features.nowplaying.components.MiniPrimaryButton
import com.calypsan.listenup.client.features.nowplaying.components.SkipGlyphs
import com.calypsan.listenup.client.features.nowplaying.miniBusyLabel
import com.calypsan.listenup.client.features.nowplaying.miniPlayLabel
import com.calypsan.listenup.client.presentation.player.MiniPlayerLine
import com.calypsan.listenup.client.presentation.player.MiniPlayerLines
import com.calypsan.listenup.client.presentation.player.PlayerProgress
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.SleepCountdowns
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_skip_backward
import org.jetbrains.compose.resources.stringResource

/** The strip's cover. */
private val ReaderStripCoverSize = 44.dp

/** The strip's main button: a plain 48dp target, the reader's dock being slimmer than the bar. */
private val ReaderStripPlaySize = DpSize(48.dp, 48.dp)

/**
 * Slim now-playing strip for the reader dock (M19): the mini player's rules, in the reader's dock.
 *
 * Intentionally minimal: cover · title · the mini line (chapter time left) · back N · the main button. No forward,
 * no speed, no expand. Renders only while a book is loaded ([PlayerUiState.Loaded]); no-ops otherwise. The main button
 * is the shared primary (play/pause, or a problem's way out), and Keep listening during the sleep fade.
 * The reader dock pairs this above a [ReaderPageScrubber].
 *
 * @param progress reads the playback position. Only the line's time left calls it, so a position tick redraws that
 *   line and nothing else in the strip.
 * @param onKeepListening The main button's action during the sleep fade: cancel the timer and restore the volume.
 */
@Composable
internal fun ReaderNowPlayingStrip(
    state: PlayerUiState,
    progress: () -> PlayerProgress,
    onPrimary: () -> Unit,
    onSkipBack: () -> Unit,
    onKeepListening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state !is PlayerUiState.Loaded) return
    val book = state.book
    val line = MiniPlayerLines.of(state)
    val skipBackSec = state.settings.skipBackSec

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BookCoverImage(
                bookId = book.id,
                coverPath = book.coverPath,
                coverHash = book.coverHash,
                // The title beside it names the book; the cover is decorative.
                contentDescription = null,
                title = book.title,
                author = book.authorLine,
                modifier =
                    Modifier
                        .size(ReaderStripCoverSize)
                        .clip(MaterialTheme.shapes.medium)
                        .clearAndSetSemantics {},
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MiniPlayerLineText(
                    line = line,
                    progress = progress,
                    sleepRemainingMs = {
                        SleepCountdowns.remainingMs(
                            state.sleepTimer,
                            progress(),
                            state.settings.speed,
                        )
                    },
                    fit = MiniLineFit.WithTimeLeft,
                )
            }
            if (state.showsSkipBack) {
                Ctrl(
                    icon = SkipGlyphs.backward(skipBackSec),
                    contentDescription = stringResource(Res.string.player_skip_backward, skipBackSec),
                    onClick = onSkipBack,
                    size = MinTouchTarget,
                    iconSize = MiniPlayerGlyphSize,
                )
            }
            MiniPrimaryButton(
                primary = state.primary,
                bookTitle = book.title,
                onClick = onPrimary,
                size = ReaderStripPlaySize,
                playLabel = miniPlayLabel(line),
                busyLabel = miniBusyLabel(line),
                onKeepListening = onKeepListening.takeIf { line == MiniPlayerLine.FadingOut },
            )
        }
    }
}
