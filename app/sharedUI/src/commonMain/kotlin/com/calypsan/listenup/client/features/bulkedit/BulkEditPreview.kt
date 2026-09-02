package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.bulk_edit_contributors
import listenup.composeapp.generated.resources.bulk_edit_genres
import listenup.composeapp.generated.resources.bulk_edit_language
import listenup.composeapp.generated.resources.bulk_edit_moods
import listenup.composeapp.generated.resources.bulk_edit_nothing_to_do
import listenup.composeapp.generated.resources.bulk_edit_nothing_to_do_hint
import listenup.composeapp.generated.resources.bulk_edit_preview_affects_none
import listenup.composeapp.generated.resources.bulk_edit_preview_affects_plural
import listenup.composeapp.generated.resources.bulk_edit_preview_affects_single_book
import listenup.composeapp.generated.resources.bulk_edit_preview_note_one
import listenup.composeapp.generated.resources.bulk_edit_preview_note_plural
import listenup.composeapp.generated.resources.bulk_edit_publisher
import listenup.composeapp.generated.resources.bulk_edit_series
import listenup.composeapp.generated.resources.bulk_edit_tags
import listenup.composeapp.generated.resources.bulk_edit_year
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val RowGap = 20.dp
private val TileSize = 42.dp
private val EmptyStateTileSize = 64.dp

/**
 * What applying would actually do, per instruction.
 *
 * Counts exclude books the instruction would not change. A bulk operation has no undo, so a number
 * that quietly included untouched books would overstate it — and this is also where someone catches
 * that they selected the wrong forty, while catching it is still free.
 *
 * Each row is named as well as counted. Three bare counts — "12 of 40", "40 of 40", "8 of 40" —
 * are honest and unusable: the one instruction the user wants to reconsider is not identifiable
 * among them. Each row also names the books it *leaves alone*, because the gap between twelve and
 * forty is otherwise the part a reader assumes is a bug.
 *
 * @param rows one line per instruction. Empty renders the resting state rather than a blank panel.
 * @param bookCount how many books are selected.
 * @param modifier Modifier for the panel.
 */
@Composable
fun BulkEditPreview(
    rows: List<BulkEditPreviewRow>,
    bookCount: Int,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        NothingToChangeYet(modifier)
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RowGap)) {
        rows.forEach { row -> PreviewRow(row = row, bookCount = bookCount) }
    }
}

/**
 * One instruction: what it changes, how much of the selection that is, and what it leaves behind.
 *
 * The bar is the count again in a form nobody has to count — the eye catches "less than a third"
 * before it parses "12 of 40", and this screen's whole job is to be understood before Apply rather
 * than after. A row that changes nothing is dimmed rather than hidden; a vanished row would read as
 * a lost edit.
 */
@Composable
private fun PreviewRow(
    row: BulkEditPreviewRow,
    bookCount: Int,
) {
    val colors = MaterialTheme.colorScheme
    val changesNothing = row.affectedCount == 0
    val ink = if (changesNothing) colors.onSurfaceVariant else colors.onSurface
    val accent = if (changesNothing) colors.onSurfaceVariant else colors.primary

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TonalIconTile(icon = iconOf(row.edit), size = TileSize, accent = accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(labelOf(row.edit)),
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    affectsText(affectedCount = row.affectedCount, bookCount = bookCount),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    textAlign = TextAlign.End,
                )
            }
            LinearProgressIndicator(
                progress = { if (bookCount <= 0) 0f else row.affectedCount.toFloat() / bookCount },
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                trackColor = colors.outlineVariant,
                // No gap and no stop mark: this is a proportion of a whole, not a job in flight,
                // and the M3 progress furniture would read as one.
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            leftAloneNote(edit = row.edit, affectedCount = row.affectedCount, bookCount = bookCount)?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * The resting state, before a single field is touched.
 *
 * Named rather than blank: an empty panel is indistinguishable from a broken one, and this is the
 * first thing every user of this screen sees.
 */
@Composable
private fun NothingToChangeYet(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TonalIconTile(
            icon = Icons.Outlined.EditNote,
            size = EmptyStateTileSize,
            accent = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.bulk_edit_nothing_to_do),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(Res.string.bulk_edit_nothing_to_do_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
 * Why the rest of the selection is left alone, or null when there is no rest to account for.
 *
 * Only the scalar instructions can name a value, so only they get a note; an "add genres" row has
 * no single word to put in the sentence, and a note that could not say what those books already
 * agree on would be noise. Silent too when the instruction changes every book — there is nothing
 * left to explain, and filler is how readers learn to skip the lines that matter.
 */
@Composable
private fun leftAloneNote(
    edit: BulkEdit,
    affectedCount: Int,
    bookCount: Int,
): String? {
    val value = valueOf(edit) ?: return null
    val leftAlone = bookCount - affectedCount
    return when {
        leftAlone <= 0 -> null
        leftAlone == 1 -> stringResource(Res.string.bulk_edit_preview_note_one, value)
        else -> stringResource(Res.string.bulk_edit_preview_note_plural, leftAlone, value)
    }
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

/** The glyph that stands for the field — the same one the form uses, where the two overlap. */
private fun iconOf(edit: BulkEdit): ImageVector =
    when (edit) {
        is BulkEdit.SetPublisher -> Icons.Outlined.Business
        is BulkEdit.SetPublishYear -> Icons.Outlined.CalendarMonth
        is BulkEdit.SetLanguage -> Icons.Outlined.Language
        is BulkEdit.AddToSeries -> Icons.Outlined.Bookmarks
        is BulkEdit.AddContributors -> Icons.Outlined.Groups
        is BulkEdit.AddGenres -> Icons.Outlined.Category
        is BulkEdit.AddTags -> Icons.Outlined.Sell
        is BulkEdit.AddMoods -> Icons.Outlined.Mood
    }

/**
 * The value an instruction writes, when it is a single value a sentence can name.
 *
 * The collection instructions return null rather than a joined list: "Fantasy, Grimdark, Space
 * Opera already say…" is not a sentence, and half a sentence in a destructive preview is worse
 * than none.
 */
private fun valueOf(edit: BulkEdit): String? =
    when (edit) {
        is BulkEdit.SetPublisher -> edit.publisher
        is BulkEdit.SetPublishYear -> edit.year.toString()
        is BulkEdit.SetLanguage -> edit.language
        is BulkEdit.AddToSeries -> null
        is BulkEdit.AddContributors -> null
        is BulkEdit.AddGenres -> null
        is BulkEdit.AddTags -> null
        is BulkEdit.AddMoods -> null
    }
