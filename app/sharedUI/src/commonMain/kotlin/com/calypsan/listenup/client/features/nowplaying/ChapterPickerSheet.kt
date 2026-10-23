package com.calypsan.listenup.client.features.nowplaying

import com.calypsan.listenup.client.design.components.formatPlaybackTime
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.components.PlayerChapterRow
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.presentation.player.PlayerChapterRow as ChapterRowState
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapters
import org.jetbrains.compose.resources.stringResource

/**
 * Chapter picker panel: every chapter as a [PlayerChapterRow], the current one highlighted. Tapping a
 * row seeks to that chapter; the panel stays open so the new highlight is visible. Adaptive
 * sheet/dialog via [PlayerPanelScaffold].
 *
 * @param chapters All chapters of the loaded book; the current one carries [ChapterRowState.isCurrent].
 * @param onChapterSelected Called with the tapped chapter's zero-based index (seeks).
 * @param onDismiss Called when the panel is dismissed.
 */
@Composable
fun ChapterPickerSheet(
    chapters: List<ChapterRowState>,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerPanelScaffold(
        title = stringResource(Res.string.player_chapters),
        onDismiss = onDismiss,
        dialogWidth = 560.dp,
        scrollable = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                chapters,
                key = { chapter -> chapter.index },
                contentType = { "chapter" },
            ) { chapter ->
                PlayerChapterRow(
                    number = chapter.number,
                    title = chapter.title,
                    durationLabel = chapter.durationMs.milliseconds.formatPlaybackTime(),
                    isCurrent = chapter.isCurrent,
                    onClick = { onChapterSelected(chapter.index) },
                )
            }
        }
    }
}
