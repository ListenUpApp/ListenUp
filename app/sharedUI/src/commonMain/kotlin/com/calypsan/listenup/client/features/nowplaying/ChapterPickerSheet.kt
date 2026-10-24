package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.formatPlaybackTime
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.nowplaying.components.PlayerChapterRow
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.features.nowplaying.components.centreOn
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow as ChapterRowState
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapters
import listenup.composeapp.generated.resources.player_chapters_count_summary
import org.jetbrains.compose.resources.stringResource

/** The chapter list's test tag. */
internal const val CHAPTER_LIST_TAG = "player-chapter-list"

/** A chapter row's test tag, by its zero-based index. */
internal fun chapterRowTag(index: Int): String = "player-chapter-$index"

/**
 * The Chapters sheet (spec §5.5.4, PC1–PC4; `Sheets-Android`): "Chapters" over "75 chapters · 45h 30m", then every
 * chapter as a [PlayerChapterRow], opening centred on the one playing (§3.11's opening rule) once the sheet has laid
 * out (§9.2). A tap plays from that chapter and closes the sheet (PC4; the view model closes it). Adaptive sheet/dialog
 * via [PlayerPanelScaffold]; the list scrolls itself, so the scaffold does not.
 *
 * @param chapters Every chapter of the loaded book; the current one carries [ChapterRowState.isCurrent].
 * @param playingLeft The current chapter's remainder ("8m"), read by the current row alone.
 * @param onChapterSelected The tapped chapter's zero-based index.
 * @param onDismiss The sheet was dismissed.
 */
@Composable
fun ChapterPickerSheet(
    chapters: List<ChapterRowState>,
    playingLeft: () -> String,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val currentPosition = chapters.indexOfFirst { it.isCurrent }
    LaunchedEffect(Unit) {
        if (currentPosition < 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        listState.centreOn(currentPosition)
    }

    PlayerPanelScaffold(
        title = stringResource(Res.string.player_chapters),
        onDismiss = onDismiss,
        dialogWidth = 560.dp,
        scrollable = false,
    ) {
        Text(
            text =
                stringResource(
                    Res.string.player_chapters_count_summary,
                    chapters.size,
                    DurationFormatter.hoursMinutes(chapters.sumOf { it.durationMs }.milliseconds),
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Spacing.sm),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).testTag(CHAPTER_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(chapters, key = {
                _,
                chapter,
                ->
                chapter.index
            }, contentType = { _, _ -> "chapter" }) { _, chapter ->
                PlayerChapterRow(
                    number = chapter.number,
                    title = chapter.title,
                    durationLabel = chapter.durationMs.milliseconds.formatPlaybackTime(),
                    isCurrent = chapter.isCurrent,
                    playingLeft = playingLeft.takeIf { chapter.isCurrent },
                    onClick = { onChapterSelected(chapter.index) },
                    modifier = Modifier.testTag(chapterRowTag(chapter.index)),
                )
            }
        }
    }
}

/** The tallest the list grows in the sheet before it scrolls. */
private val LIST_MAX_HEIGHT = 560.dp
