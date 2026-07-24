package com.calypsan.listenup.client.features.nowplaying.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.bookdetail.components.CountBadge
import com.calypsan.listenup.client.features.nowplaying.formatPlaybackTime
import com.calypsan.listenup.client.playback.NowPlayingChapter
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_up_next
import listenup.composeapp.generated.resources.player_view_all_chapters
import org.jetbrains.compose.resources.stringResource

// Maximum number of chapter rows shown in the preview slice before the "View all" footer.
private const val QUEUE_PREVIEW_SIZE = 8

/**
 * "Up next" queue card for the wide Now Playing layout.
 *
 * Renders a [RoundedCornerShape] card with [surfaceContainerLow] background containing:
 * - A header row with "Up next" [titleLarge] label and a [CountBadge] showing [totalChapters].
 * - A preview slice of up to [QUEUE_PREVIEW_SIZE] [PlayerChapterRow]s, each tappable to seek.
 * - A full-width "View all N chapters" footer button to open the chapter picker.
 *
 * The card itself is layout-agnostic — callers constrain it via [Modifier.widthIn] and
 * [Modifier.weight] as appropriate for the two-pane wide layout.
 *
 * @param chapters Full chapter list from [NowPlayingState.Active]. Only the first
 *   [QUEUE_PREVIEW_SIZE] entries are shown; all chapters are available via [onViewAllChapters].
 * @param currentChapterIndex Zero-based index of the currently playing chapter.
 * @param totalChapters Total number of chapters in the book, shown in the badge and footer label.
 * @param onSeekToChapter Called with the zero-based chapter index when a row is tapped.
 * @param onViewAllChapters Called when the "View all N chapters" footer button is tapped.
 * @param modifier Optional layout modifier applied to the outer card surface.
 */
@Composable
fun UpNextQueue(
    chapters: List<NowPlayingChapter>,
    currentChapterIndex: Int,
    totalChapters: Int,
    onSeekToChapter: (Int) -> Unit,
    onViewAllChapters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
        ) {
            // Header: "Up next" label + chapter count badge.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.player_up_next),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                CountBadge(count = totalChapters)
            }

            Spacer(Modifier.height(10.dp))

            // Chapter preview slice — at most QUEUE_PREVIEW_SIZE rows.
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                chapters.take(QUEUE_PREVIEW_SIZE).forEach { chapter ->
                    PlayerChapterRow(
                        number = chapter.index + 1,
                        title = chapter.title,
                        durationLabel = chapter.durationMs.milliseconds.formatPlaybackTime(),
                        isCurrent = chapter.index == currentChapterIndex,
                        onClick = { onSeekToChapter(chapter.index) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Push footer to bottom of the card.
            Spacer(Modifier.weight(1f))

            Spacer(Modifier.height(16.dp))

            // Footer: "View all N chapters" button.
            Surface(
                onClick = onViewAllChapters,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.player_view_all_chapters, totalChapters),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}
