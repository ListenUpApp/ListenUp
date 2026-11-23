package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.input.TextFieldState
import com.calypsan.listenup.client.design.components.AdaptiveSheet
import com.calypsan.listenup.client.design.components.transformedBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.client.core.ChapterTimeFormat
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterMarkerTier
import com.calypsan.listenup.client.presentation.chaptereditor.moveTargets
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_marker_edit_book
import listenup.composeapp.generated.resources.chapter_editor_marker_edit_part
import listenup.composeapp.generated.resources.chapter_editor_marker_move
import listenup.composeapp.generated.resources.chapter_editor_marker_move_title
import listenup.composeapp.generated.resources.chapter_editor_marker_name
import listenup.composeapp.generated.resources.chapter_editor_marker_new_book
import listenup.composeapp.generated.resources.chapter_editor_marker_new_part
import listenup.composeapp.generated.resources.chapter_editor_marker_remove
import listenup.composeapp.generated.resources.chapter_editor_marker_starts_at
import listenup.composeapp.generated.resources.chapter_editor_marker_starts_at_value
import listenup.composeapp.generated.resources.common_save
import org.jetbrains.compose.resources.stringResource

private val PICKER_ROW_MIN_HEIGHT = 56.dp

/**
 * Which marker the sheet is open on.
 *
 * @property chapterId the chapter carrying it, or about to.
 * @property tier the level it opens.
 * @property isNew nothing is written until Save, so there is nothing yet to move or remove.
 */
@Immutable
data class ChapterMarkerEdit(
    val chapterId: String,
    val tier: ChapterMarkerTier,
    val isNew: Boolean,
)

/**
 * Naming a marker and placing it, in a sheet.
 *
 * @param initialName what the field opens on: the marker's name, or a default for a new one.
 * @param onSave the trimmed name, and the chapter it should start at.
 */
@Composable
internal fun ChapterMarkerSheet(
    edit: ChapterMarkerEdit,
    initialName: String,
    chapters: List<Chapter>,
    onSave: (String, String) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var isPicking by rememberSaveable(edit) { mutableStateOf(false) }

    AdaptiveSheet(
        title = if (isPicking) stringResource(Res.string.chapter_editor_marker_move_title) else edit.sheetTitle(),
        onDismiss = onDismiss,
        scrollable = !isPicking,
    ) { _ ->
        ChapterMarkerSheetContent(
            edit = edit,
            initialName = initialName,
            chapters = chapters,
            isPicking = isPicking,
            onMove = { isPicking = true },
            onPicked = { isPicking = false },
            onSave = onSave,
            onRemove = onRemove,
            modifier = if (isPicking) Modifier.weight(1f, fill = false) else Modifier,
        )
    }
}

/** The sheet's body. It swaps to the picker in place, so Move never stacks a sheet on a sheet. */
@Composable
private fun ChapterMarkerSheetContent(
    edit: ChapterMarkerEdit,
    initialName: String,
    chapters: List<Chapter>,
    isPicking: Boolean,
    onMove: () -> Unit,
    onPicked: () -> Unit,
    onSave: (String, String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Saveable, so a rotation keeps what was typed and chosen; keyed by the marker, so a different
    // one opens fresh.
    val name = rememberSaveable(edit, saver = TextFieldState.Saver) { TextFieldState(initialName) }
    var startsAt by rememberSaveable(edit) { mutableStateOf(edit.chapterId) }

    if (isPicking) {
        MarkerStartPicker(
            chapters = chapters,
            tier = edit.tier,
            onPick = { chapterId ->
                startsAt = chapterId
                onPicked()
            },
            modifier = modifier,
        )
    } else {
        MarkerForm(
            edit = edit,
            name = name,
            startsAt = chapters.numbered().firstOrNull { it.chapter.id == startsAt },
            onMove = onMove,
            onSave = { onSave(name.text.toString().trim(), startsAt) },
            onRemove = onRemove,
            modifier = modifier,
        )
    }
}

@Composable
private fun MarkerForm(
    edit: ChapterMarkerEdit,
    name: TextFieldState,
    startsAt: NumberedChapter?,
    onMove: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ListenUpTextField(
            state = name,
            label = stringResource(Res.string.chapter_editor_marker_name),
            modifier = Modifier.fillMaxWidth(),
            inputTransformation = markerNameLimit,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        startsAt?.let { MarkerStartRow(it, onMove.takeUnless { edit.isNew }) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!edit.isNew) {
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(Res.string.chapter_editor_marker_remove),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            ListenUpButton(
                text = stringResource(Res.string.common_save),
                onClick = onSave,
                enabled = name.text.isNotBlank(),
                fillMaxWidth = false,
            )
        }
    }
}

/** Where the marker starts, and with [onMove] the way to change it. */
@Composable
private fun MarkerStartRow(
    startsAt: NumberedChapter,
    onMove: (() -> Unit)?,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = PICKER_ROW_MIN_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.chapter_editor_marker_starts_at),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text =
                    stringResource(
                        Res.string.chapter_editor_marker_starts_at_value,
                        startsAt.number,
                        startsAt.chapter.title,
                        ChapterTimeFormat.clock(startsAt.chapter.startTime),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onMove != null) {
            TextButton(onClick = onMove) { Text(stringResource(Res.string.chapter_editor_marker_move)) }
        }
    }
}

/** The chapters this marker can move to. One already opening the tier is not among them. */
@Composable
private fun MarkerStartPicker(
    chapters: List<Chapter>,
    tier: ChapterMarkerTier,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val numbers = remember(chapters) { chapters.numbered().associate { it.chapter.id to it.number } }
    val targets = remember(chapters, tier) { chapters.moveTargets(tier) }

    Column(modifier.fillMaxWidth().padding(top = Spacing.sm)) {
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(targets, key = { it.id }) { chapter ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = PICKER_ROW_MIN_HEIGHT)
                        .clickable { onPick(chapter.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${numbers[chapter.id]}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = chapter.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ChapterTimeFormat.clock(chapter.startTime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterMarkerEdit.sheetTitle(): String =
    stringResource(
        when {
            isNew && tier == ChapterMarkerTier.Part -> Res.string.chapter_editor_marker_new_part
            isNew -> Res.string.chapter_editor_marker_new_book
            tier == ChapterMarkerTier.Part -> Res.string.chapter_editor_marker_edit_part
            else -> Res.string.chapter_editor_marker_edit_book
        },
    )

private val markerNameLimit = transformedBy { it.take(ChapterInput.MAX_SECTION_TITLE) }
