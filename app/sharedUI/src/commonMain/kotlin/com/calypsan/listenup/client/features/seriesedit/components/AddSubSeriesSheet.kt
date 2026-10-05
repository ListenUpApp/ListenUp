package com.calypsan.listenup.client.features.seriesedit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpSearchField
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.seriesdetail.components.SeriesCoverThumb
import com.calypsan.listenup.client.features.seriesdetail.components.bookCountLabel
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import com.calypsan.listenup.client.presentation.seriesedit.SubSeriesCandidateUi
import com.calypsan.listenup.client.presentation.seriesedit.SubSeriesPlacement
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_create
import listenup.composeapp.generated.resources.series_add_existing_instead
import listenup.composeapp.generated.resources.series_add_subseries_to
import listenup.composeapp.generated.resources.series_merge_no_matches
import listenup.composeapp.generated.resources.series_merge_search_placeholder
import listenup.composeapp.generated.resources.series_move_confirm_action
import listenup.composeapp.generated.resources.series_move_confirm_title
import listenup.composeapp.generated.resources.series_new_series
import listenup.composeapp.generated.resources.series_new_series_title
import listenup.composeapp.generated.resources.series_new_subseries_body
import listenup.composeapp.generated.resources.series_picker_already_in
import listenup.composeapp.generated.resources.series_picker_moves_here
import listenup.composeapp.generated.resources.series_top_level
import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import org.jetbrains.compose.resources.stringResource

/**
 * "Add sub-series to Cosmere" — shared by the series page's tile and the editor's button. A bottom
 * sheet of every series that could go here (search, "New series…", then each with where it sits
 * today), the "Move City Watch out of Discworld into Cosmere?" confirmation, and the "New series"
 * dialog. A refusal is already on the global snackbar (the ViewModel sends it to the error bus), so
 * the sheet only acknowledges it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubSeriesSheet(
    state: AddSubSeriesUiState,
    onEvent: (AddSubSeriesEvent) -> Unit,
) {
    LaunchedEffect(state.error) {
        if (state.error != null) onEvent(AddSubSeriesEvent.ErrorDismissed)
    }
    val open = state as? AddSubSeriesUiState.Open ?: return

    ModalBottomSheet(
        onDismissRequest = { onEvent(AddSubSeriesEvent.Dismissed) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = BottomSheetDefaults.ExpandedShape,
    ) {
        AddSubSeriesSheetContent(state = open, onEvent = onEvent)
    }

    open.pendingMove?.let { move ->
        ListenUpAlertDialog(
            onDismissRequest = { onEvent(AddSubSeriesEvent.MoveCancelled) },
            title =
                stringResource(
                    Res.string.series_move_confirm_title,
                    move.seriesName,
                    move.fromParentName,
                    move.toParentName,
                ),
            confirmText = stringResource(Res.string.series_move_confirm_action),
            onConfirm = { onEvent(AddSubSeriesEvent.MoveConfirmed) },
            dismissText = stringResource(Res.string.common_cancel),
            onDismiss = { onEvent(AddSubSeriesEvent.MoveCancelled) },
        ) {}
    }

    open.newSeries?.let { draft ->
        SeriesNameDialog(
            title = stringResource(Res.string.series_new_series_title),
            draft = draft,
            body = stringResource(Res.string.series_new_subseries_body, draft.name.trim(), open.parentName),
            confirmLabel = stringResource(Res.string.common_create),
            useExistingLabel = stringResource(Res.string.series_add_existing_instead),
            onNameChange = { onEvent(AddSubSeriesEvent.NewSeriesNameChanged(it)) },
            onConfirm = { onEvent(AddSubSeriesEvent.NewSeriesConfirmed) },
            onUseExisting = { onEvent(AddSubSeriesEvent.Chosen(it)) },
            onDismiss = { onEvent(AddSubSeriesEvent.NewSeriesDismissed) },
            busy = open.isBusy,
        )
    }
}

/** The sheet's body: title, search, "New series…", and the candidates. */
@Composable
internal fun AddSubSeriesSheetContent(
    state: AddSubSeriesUiState.Open,
    onEvent: (AddSubSeriesEvent) -> Unit,
) {
    val haptics = LocalHaptics.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.series_add_subseries_to, state.parentName),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = Spacing.screenMargin).semantics { heading() },
        )
        ListenUpSearchField(
            value = state.query,
            onValueChange = { onEvent(AddSubSeriesEvent.QueryChanged(it)) },
            onSubmit = {},
            placeholder = stringResource(Res.string.series_merge_search_placeholder),
            onClear = { onEvent(AddSubSeriesEvent.QueryChanged("")) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin, vertical = 12.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "new") {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(Res.string.series_new_series),
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier =
                        Modifier.clickable {
                            haptics.press()
                            onEvent(AddSubSeriesEvent.NewSeriesStarted)
                        },
                )
            }
            if (state.candidates.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(Res.string.series_merge_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.screenMargin),
                    )
                }
            }
            items(state.candidates, key = { it.id }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    parentName = state.parentName,
                    onClick = {
                        haptics.press()
                        onEvent(AddSubSeriesEvent.Chosen(candidate.id))
                    },
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: SubSeriesCandidateUi,
    parentName: String,
    onClick: () -> Unit,
) {
    val meta =
        when (candidate.placement) {
            SubSeriesPlacement.TOP_LEVEL -> {
                "${stringResource(Res.string.series_top_level)} · ${bookCountLabel(candidate.bookCount)}"
            }

            SubSeriesPlacement.IN_OTHER_PARENT -> {
                stringResource(Res.string.series_picker_moves_here, candidate.currentParentName.orEmpty())
            }

            SubSeriesPlacement.ALREADY_HERE -> {
                stringResource(Res.string.series_picker_already_in, parentName)
            }
        }
    ListItem(
        headlineContent = { Text(candidate.name, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(meta) },
        leadingContent = {
            SeriesCoverThumb(
                seriesId = candidate.id,
                name = candidate.name,
                coverPath = candidate.coverPath,
                size = 48.dp,
            )
        },
        modifier =
            if (candidate.isSelectable) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier.alpha(DISABLED_ALPHA).semantics { disabled() }
            },
    )
}

private const val DISABLED_ALPHA = 0.5f
