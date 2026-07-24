package com.calypsan.listenup.client.features.metadata

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ExpressiveCheckbox
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.presentation.metadata.ChapterNameRow
import com.calypsan.listenup.client.presentation.metadata.ChapterSuggestion
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.metadata_all_n_selected
import listenup.composeapp.generated.resources.metadata_apply_chapter_names
import listenup.composeapp.generated.resources.metadata_chapter_names_replace_note
import listenup.composeapp.generated.resources.metadata_review_chapter_names

/** Inset start of the row dividers — clears the leading checkbox column. */
private val CHAPTER_DIVIDER_INSET = 56.dp

/**
 * Modal sheet for reviewing Audible chapter-name suggestions before applying.
 *
 * Reframes each chapter as a generic → named replacement: the current label struck through, an
 * arrow, then the suggested name, with a per-row [ExpressiveCheckbox] (default on). The Apply button
 * renames only the checked chapters; chapter timings are never touched.
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
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
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
                    .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(Res.string.metadata_review_chapter_names),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(Res.string.metadata_chapter_names_replace_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 14.dp),
            )

            Text(
                text = stringResource(Res.string.metadata_all_n_selected, available.selectedOrdinals.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
            )

            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                LazyColumn {
                    items(available.rows, key = { it.ordinal }) { row ->
                        ChapterReviewRow(
                            row = row,
                            checked = row.ordinal in available.selectedOrdinals,
                            showDivider = row.ordinal != available.rows.first().ordinal,
                            onToggle = { onToggle(row.ordinal) },
                        )
                    }
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

            ListenUpButton(
                text = stringResource(Res.string.metadata_apply_chapter_names),
                onClick = onApply,
                enabled = available.selectedOrdinals.isNotEmpty(),
                isLoading = available.isApplying,
                leadingIcon = Icons.AutoMirrored.Outlined.ArrowForward,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        }
    }
}

/**
 * A single chapter row reframed as generic → named: the current label struck through, an arrow, then
 * the Audible suggestion, with a leading [ExpressiveCheckbox] controlling whether the rename applies.
 */
@Composable
private fun ChapterReviewRow(
    row: ChapterNameRow,
    checked: Boolean,
    showDivider: Boolean,
    onToggle: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = CHAPTER_DIVIDER_INSET),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExpressiveCheckbox(checked = checked)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        text = row.currentName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = row.suggestedName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
