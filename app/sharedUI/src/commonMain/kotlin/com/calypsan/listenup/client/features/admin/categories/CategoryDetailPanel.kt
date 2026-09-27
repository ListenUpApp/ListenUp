package com.calypsan.listenup.client.features.admin.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.Genre
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_add_subgenre
import listenup.composeapp.generated.resources.admin_categories_select_hint
import listenup.composeapp.generated.resources.admin_merge_into
import listenup.composeapp.generated.resources.admin_move_to
import listenup.composeapp.generated.resources.admin_top_level
import listenup.composeapp.generated.resources.common_book_count
import listenup.composeapp.generated.resources.common_books_count
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_rename
import listenup.composeapp.generated.resources.merge_history_open
import org.jetbrains.compose.resources.stringResource

/** Width of the wide layout's category detail panel — one comfortable settings-card column. */
internal val CategoryDetailPanelWidth = 360.dp

/** Room left under the panel's last row so the add-genre FAB never sits on top of Delete. */
private val FabClearance = 80.dp

/**
 * The wide layout's detail for the selected category: where it sits in the tree, how many books
 * carry it, and every action the phone keeps behind a long-press, laid out as rows. With nothing
 * selected it says how to pick one.
 *
 * @param genre The selected category, or null when none is selected (or it was just deleted).
 *
 * One callback per category action, the same set the tree rows' menus take; a parameter object
 * would only add an indirection layer Compose tooling discourages.
 */
@Suppress("LongParameterList")
@Composable
internal fun CategoryDetailPanel(
    genre: Genre?,
    onAddChild: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onMergeHistory: (String) -> Unit,
    onMove: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = FabClearance),
        verticalArrangement = Arrangement.spacedBy(Spacing.titleGap),
    ) {
        if (genre == null) {
            NoCategorySelected()
        } else {
            CategoryHeader(genre = genre)
            CategoryActions(
                onAddChild = { onAddChild(genre.id, genre.name) },
                onRename = { onRename(genre.id, genre.name) },
                onMove = { onMove(genre.id, genre.name) },
                onMerge = { onMerge(genre.id, genre.name) },
                onMergeHistory = { onMergeHistory(genre.id) },
                onDelete = { onDelete(genre.id, genre.name) },
            )
        }
    }
}

@Composable
private fun NoCategorySelected() {
    Icon(
        imageVector = Icons.Outlined.Category,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sectionGap).size(40.dp),
    )
    Text(
        text = stringResource(Res.string.admin_categories_select_hint),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CategoryHeader(genre: Genre) {
    Column {
        Text(
            text = genre.name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = genre.parentPath ?: stringResource(Res.string.admin_top_level),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                stringResource(
                    if (genre.bookCount == 1) Res.string.common_book_count else Res.string.common_books_count,
                    genre.bookCount,
                ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// One row per category action; a parameter object would only add an indirection layer Compose
// tooling discourages.
@Suppress("LongParameterList")
@Composable
private fun CategoryActions(
    onAddChild: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onMerge: () -> Unit,
    onMergeHistory: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            SettingRow(
                title = stringResource(Res.string.admin_add_subgenre),
                icon = Icons.Outlined.Add,
                onClick = onAddChild,
            )
            SettingRow(
                title = stringResource(Res.string.common_rename),
                icon = Icons.Outlined.Edit,
                showDivider = true,
                onClick = onRename,
            )
            SettingRow(
                title = stringResource(Res.string.admin_move_to),
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                showDivider = true,
                onClick = onMove,
            )
            SettingRow(
                title = stringResource(Res.string.admin_merge_into),
                icon = Icons.AutoMirrored.Outlined.CallMerge,
                showDivider = true,
                onClick = onMerge,
            )
            SettingRow(
                title = stringResource(Res.string.merge_history_open),
                icon = Icons.Outlined.History,
                showDivider = true,
                onClick = onMergeHistory,
            )
            SettingRow(
                title = stringResource(Res.string.common_delete),
                icon = Icons.Outlined.Delete,
                danger = true,
                showDivider = true,
                onClick = onDelete,
            )
        }
    }
}
