package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.features.nowplaying.components.PlayerChapterRow
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.player_chapters
import org.jetbrains.compose.resources.stringResource

/**
 * Chapter picker panel: every chapter as a [PlayerChapterRow], the current one highlighted. Tapping a
 * row seeks to that chapter; the panel stays open so the new highlight is visible. Adaptive
 * sheet/dialog via [PlayerPanelScaffold].
 *
 * @param chapters All chapters of the playing book.
 * @param currentChapterIndex Zero-based index of the chapter now playing.
 * @param onChapterSelected Called with the tapped chapter's zero-based index (seeks).
 * @param onDismiss Called when the panel is dismissed.
 */
@Composable
fun ChapterPickerSheet(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerPanelScaffold(
        title = stringResource(Res.string.player_chapters),
        onDismiss = onDismiss,
        dialogWidth = 560.dp,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(
                chapters,
                key = { _, chapter -> chapter.id },
                contentType = { _, _ -> "chapter" },
            ) { index, chapter ->
                PlayerChapterRow(
                    number = index + 1,
                    title = chapter.title,
                    durationLabel = chapter.duration.milliseconds.formatPlaybackTime(),
                    isCurrent = index == currentChapterIndex,
                    onClick = { onChapterSelected(index) },
                )
            }
        }
    }
}
