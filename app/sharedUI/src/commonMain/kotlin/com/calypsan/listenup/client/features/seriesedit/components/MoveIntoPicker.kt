package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpSearchField
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.seriesdetail.components.bookCountLabel
import com.calypsan.listenup.client.features.seriesdetail.components.seriesPathLabel
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerDisabledReason
import com.calypsan.listenup.client.presentation.seriesedit.ParentPickerRow
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_collapse
import listenup.composeapp.generated.resources.common_expand
import listenup.composeapp.generated.resources.series_create_and_move
import listenup.composeapp.generated.resources.series_merge_no_matches
import listenup.composeapp.generated.resources.series_merge_search_placeholder
import listenup.composeapp.generated.resources.series_move_into_existing
import listenup.composeapp.generated.resources.series_move_into_named
import listenup.composeapp.generated.resources.series_new_parent
import listenup.composeapp.generated.resources.series_new_parent_body
import listenup.composeapp.generated.resources.series_new_parent_title
import listenup.composeapp.generated.resources.series_picker_current
import listenup.composeapp.generated.resources.series_picker_inside
import listenup.composeapp.generated.resources.series_picker_self
import listenup.composeapp.generated.resources.series_subseries_count
import listenup.composeapp.generated.resources.series_top_level_no_parent
import org.jetbrains.compose.resources.stringResource

private const val DISABLED_ALPHA = 0.5f
private val IndentPerLevel = 20.dp

/** The "Move into…" picker as a bottom sheet — the phone and small-tablet presentation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveIntoPickerSheet(
    state: SeriesEditUiState,
    rows: List<ParentPickerRow>,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onEvent(SeriesEditUiEvent.ParentPickerDismissed) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = BottomSheetDefaults.ExpandedShape,
    ) {
        MoveIntoPicker(state = state, rows = rows, onEvent = onEvent)
    }
}

/**
 * "Move “Mistborn” into…": search, the two pinned choices (Top level, New parent series…), then the
 * whole tree. What can't be chosen stays in the tree, greyed, with its reason — "Current", "This
 * series", "Inside Mistborn" — so the library's shape never jumps. While searching the rows are
 * flat, each saying where it sits.
 */
@Composable
internal fun MoveIntoPicker(
    state: SeriesEditUiState,
    rows: List<ParentPickerRow>,
    onEvent: (SeriesEditUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val searching = state.parentQuery.isNotBlank()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.series_move_into_named, state.name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = Spacing.screenMargin).semantics { heading() },
        )
        ListenUpSearchField(
            value = state.parentQuery,
            onValueChange = { onEvent(SeriesEditUiEvent.ParentQueryChanged(it)) },
            onSubmit = {},
            placeholder = stringResource(Res.string.series_merge_search_placeholder),
            onClear = { onEvent(SeriesEditUiEvent.ParentQueryChanged("")) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin, vertical = 12.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "top-level") {
                val isTopLevel = state.parentId == null
                PickerRowLayout(
                    title = stringResource(Res.string.series_top_level_no_parent),
                    meta = null,
                    depth = 0,
                    leading = { Icon(Icons.Outlined.Home, contentDescription = null) },
                    reason = if (isTopLevel) stringResource(Res.string.series_picker_current) else null,
                    onChoose = {
                        haptics.press()
                        onEvent(SeriesEditUiEvent.ParentCleared)
                    },
                )
            }
            item(key = "new-parent") {
                PickerRowLayout(
                    title = stringResource(Res.string.series_new_parent),
                    meta = null,
                    depth = 0,
                    leading = { Icon(Icons.Default.Add, contentDescription = null) },
                    reason = null,
                    onChoose = {
                        haptics.press()
                        onEvent(SeriesEditUiEvent.NewParentStarted)
                    },
                )
            }
            if (searching && rows.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(Res.string.series_merge_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.screenMargin),
                    )
                }
            }
            items(rows, key = { it.id }) { row ->
                TreeRow(row = row, seriesName = state.name, searching = searching, onEvent = onEvent)
            }
        }
    }
}

@Composable
private fun TreeRow(
    row: ParentPickerRow,
    seriesName: String,
    searching: Boolean,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    val haptics = LocalHaptics.current
    val reason =
        when (row.disabledReason) {
            ParentPickerDisabledReason.CURRENT_PARENT -> stringResource(Res.string.series_picker_current)
            ParentPickerDisabledReason.THIS_SERIES -> stringResource(Res.string.series_picker_self)
            ParentPickerDisabledReason.INSIDE_THIS_SERIES -> stringResource(Res.string.series_picker_inside, seriesName)
            null -> null
        }
    val counts =
        listOfNotNull(
            bookCountLabel(row.bookCount),
            if (row.subSeriesCount > 0) stringResource(Res.string.series_subseries_count, row.subSeriesCount) else null,
        ).joinToString(" · ")
    val meta = if (searching) seriesPathLabel(row.pathNames)?.let { "$it · $counts" } ?: counts else counts
    PickerRowLayout(
        title = row.name,
        meta = meta,
        depth = row.depth,
        leading = {
            if (row.hasChildren && !searching) {
                IconButton(
                    onClick = {
                        haptics.selectionTick()
                        onEvent(SeriesEditUiEvent.ParentPickerNodeToggled(row.id))
                    },
                ) {
                    Icon(
                        imageVector =
                            if (row.isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription =
                            "${stringResource(if (row.isExpanded) Res.string.common_collapse else Res.string.common_expand)} ${row.name}",
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        },
        reason = reason,
        onChoose = {
            haptics.press()
            onEvent(SeriesEditUiEvent.ParentSelected(row.id))
        },
    )
}

/**
 * One picker row. A row with a [reason] can't be chosen: it is greyed, marked disabled, and reads
 * "Mistborn Era 1, Inside Mistborn" so the reason travels with the name.
 */
@Composable
private fun PickerRowLayout(
    title: String,
    meta: String?,
    depth: Int,
    leading: @Composable () -> Unit,
    reason: String?,
    onChoose: () -> Unit,
) {
    val enabled = reason == null
    val description = listOfNotNull(title, reason, meta).joinToString(", ")
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = Spacing.sm + IndentPerLevel * depth, end = Spacing.screenMargin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .then(if (enabled) Modifier.clickable(onClick = onChoose) else Modifier.alpha(DISABLED_ALPHA))
                    .clearAndSetSemantics {
                        contentDescription = description
                        if (!enabled) disabled()
                        if (enabled) {
                            onClick {
                                onChoose()
                                true
                            }
                        }
                    }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (meta != null) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (reason != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(reason, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** "New parent series": creates the parent where the series sits now and moves it in. */
@Composable
internal fun NewParentDialog(
    state: SeriesEditUiState,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    val draft = state.newParent ?: return
    SeriesNameDialog(
        title = stringResource(Res.string.series_new_parent_title),
        draft = draft,
        body = stringResource(Res.string.series_new_parent_body, draft.name.trim(), state.name),
        confirmLabel = stringResource(Res.string.series_create_and_move),
        useExistingLabel = stringResource(Res.string.series_move_into_existing),
        onNameChange = { onEvent(SeriesEditUiEvent.NewParentNameChanged(it)) },
        onConfirm = { onEvent(SeriesEditUiEvent.NewParentConfirmed) },
        onUseExisting = { id ->
            onEvent(SeriesEditUiEvent.ParentSelected(id))
            onEvent(SeriesEditUiEvent.NewParentDismissed)
        },
        onDismiss = { onEvent(SeriesEditUiEvent.NewParentDismissed) },
        busy = state.hierarchyBusy,
    )
}
