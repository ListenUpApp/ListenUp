package com.calypsan.listenup.client.features.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.presentation.metadata.ChapterNameRow
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.metadata_apply_chapter_names
import listenup.composeapp.generated.resources.metadata_chapter_names_replace_note
import listenup.composeapp.generated.resources.metadata_review_chapter_names

/**
 * Modal sheet for reviewing Audible chapter-name suggestions before applying.
 *
 * Shows each chapter's current → suggested name with a per-row checkbox
 * (default on). The Apply button renames only the checked chapters; chapter
 * timings are never touched. Mirrors the [ShelfPickerSheet] sheet idiom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterNameReviewSheet(
    available: ChapterSuggestion.Available,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Surface(
                modifier =
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(32.dp)
                        .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ) {}
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(Res.string.metadata_review_chapter_names),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(Res.string.metadata_chapter_names_replace_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(available.rows, key = { it.ordinal }) { row ->
                    ChapterReviewRow(
                        row = row,
                        checked = row.ordinal in available.selectedOrdinals,
                        onToggle = { onToggle(row.ordinal) },
                    )
                }
            }

            available.applyError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(
                onClick = onApply,
                enabled = !available.isApplying && available.selectedOrdinals.isNotEmpty(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
            ) {
                if (available.isApplying) {
                    ListenUpLoadingIndicatorSmall()
                } else {
                    Text(stringResource(Res.string.metadata_apply_chapter_names))
                }
            }
        }
    }
}

/**
 * A single chapter row showing the current label above the Audible suggestion,
 * with a checkbox controlling whether the rename is applied.
 */
@Composable
private fun ChapterReviewRow(
    row: ChapterNameRow,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.currentName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.suggestedName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
