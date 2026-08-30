package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.bulk_edit_contributors
import listenup.composeapp.generated.resources.bulk_edit_genres
import listenup.composeapp.generated.resources.bulk_edit_language
import listenup.composeapp.generated.resources.bulk_edit_moods
import listenup.composeapp.generated.resources.bulk_edit_preview_affects_none
import listenup.composeapp.generated.resources.bulk_edit_preview_affects_plural
import listenup.composeapp.generated.resources.bulk_edit_preview_affects_single_book
import listenup.composeapp.generated.resources.bulk_edit_publisher
import listenup.composeapp.generated.resources.bulk_edit_series
import listenup.composeapp.generated.resources.bulk_edit_tags
import listenup.composeapp.generated.resources.bulk_edit_year
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What applying would actually do, per instruction.
 *
 * Counts exclude books the instruction would not change. A bulk operation has no undo, so a number
 * that quietly included untouched books would overstate it — and this is also where someone catches
 * that they selected the wrong forty, while catching it is still free.
 *
 * Each row is named as well as counted. Three bare counts — "12 of 40", "40 of 40", "8 of 40" —
 * are honest and unusable: the one instruction the user wants to reconsider is not identifiable
 * among them.
 *
 * @param rows one line per instruction.
 * @param bookCount how many books are selected.
 * @param modifier Modifier for the panel.
 */
@Composable
fun BulkEditPreview(
    rows: List<BulkEditPreviewRow>,
    bookCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceContainerLow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            val changesNothing = row.affectedCount == 0
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(labelOf(row.edit)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (changesNothing) colors.onSurfaceVariant else colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    affectsText(affectedCount = row.affectedCount, bookCount = bookCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (changesNothing) colors.onSurfaceVariant else colors.onSurface,
                )
            }
        }
    }
}

/**
 * How much of the selection one instruction touches, in words.
 *
 * A single selected book gets its own sentence rather than the counted one, because "1 of 1 books
 * change" is a sentence no reader needs to parse to learn something they already know.
 */
@Composable
private fun affectsText(
    affectedCount: Int,
    bookCount: Int,
): String =
    when {
        affectedCount == 0 -> stringResource(Res.string.bulk_edit_preview_affects_none)
        bookCount == 1 -> stringResource(Res.string.bulk_edit_preview_affects_single_book)
        else -> stringResource(Res.string.bulk_edit_preview_affects_plural, affectedCount, bookCount)
    }

/**
 * The field an instruction changes, as the form labels it.
 *
 * Exhaustive over [BulkEdit] on purpose: a ninth instruction is a compile error here, which is the
 * only reliable reminder that a new field also needs a name in the preview. A row that could not
 * name itself would be worse than no row.
 */
private fun labelOf(edit: BulkEdit): StringResource =
    when (edit) {
        is BulkEdit.SetPublisher -> Res.string.bulk_edit_publisher
        is BulkEdit.SetPublishYear -> Res.string.bulk_edit_year
        is BulkEdit.SetLanguage -> Res.string.bulk_edit_language
        is BulkEdit.AddToSeries -> Res.string.bulk_edit_series
        is BulkEdit.AddContributors -> Res.string.bulk_edit_contributors
        is BulkEdit.AddGenres -> Res.string.bulk_edit_genres
        is BulkEdit.AddTags -> Res.string.bulk_edit_tags
        is BulkEdit.AddMoods -> Res.string.bulk_edit_moods
    }
