package com.calypsan.listenup.web.features.seriesedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesEvent
import com.calypsan.listenup.client.presentation.seriesedit.AddSubSeriesUiState
import com.calypsan.listenup.client.presentation.seriesedit.NewSeriesDraft
import com.calypsan.listenup.client.presentation.seriesedit.PendingSubSeriesMove
import com.calypsan.listenup.client.presentation.seriesedit.SubSeriesCandidateUi
import com.calypsan.listenup.client.presentation.seriesedit.SubSeriesPlacement
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.DialogActions
import com.calypsan.listenup.web.design.DialogText
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.features.seriesdetail.seriesBookCount
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * "Add sub-series to Cosmere" — the one dialog the series page's tile and the editor's button both
 * open, over the shared `SubSeriesAdder`.
 *
 * One dialog at a time, never stacked: the list, then — when a choice needs it — the confirmation
 * ("Move City Watch out of Discworld into Cosmere?") or the "New series" form in its place. Backing
 * out of either (Cancel or Escape) returns to the list, which is what the ViewModel's
 * `MoveCancelled`/`NewSeriesDismissed` do.
 *
 * A refusal from the server already reaches the shell's toast through the error bus, so the error
 * this state carries only has to be acknowledged here — showing it a second time would say it twice.
 */
@Composable
fun AddSubSeriesDialogs(
    state: AddSubSeriesUiState,
    onEvent: (AddSubSeriesEvent) -> Unit,
) {
    val error = state.error
    LaunchedEffect(error) { if (error != null) onEvent(AddSubSeriesEvent.ErrorDismissed) }

    if (state !is AddSubSeriesUiState.Open) return
    val pending = state.pendingMove
    val draft = state.newSeries
    when {
        pending != null -> {
            key("confirm") { MoveConfirmDialog(pending, onEvent) }
        }

        draft != null -> {
            key("new") {
                NewSeriesNameDialog(
                    // en.json's series.new_series_title
                    title = "New series",
                    draft = draft,
                    // en.json's series.new_subseries_body
                    body = "Creates “${draft.name.trim().ifEmpty { "…" }}” inside ${state.parentName}.",
                    // en.json's common.create
                    confirmLabel = "Create",
                    // en.json's series.add_existing_instead
                    useExistingLabel = "Add it instead",
                    onName = { onEvent(AddSubSeriesEvent.NewSeriesNameChanged(it)) },
                    onConfirm = { onEvent(AddSubSeriesEvent.NewSeriesConfirmed) },
                    onUseExisting = { onEvent(AddSubSeriesEvent.Chosen(it)) },
                    onDismiss = { onEvent(AddSubSeriesEvent.NewSeriesDismissed) },
                    idBase = "sub-new",
                )
            }
        }

        else -> {
            key("list") { CandidateList(state, onEvent) }
        }
    }
}

@Composable
private fun CandidateList(
    state: AddSubSeriesUiState.Open,
    onEvent: (AddSubSeriesEvent) -> Unit,
) {
    ModalDialog(
        open = true,
        // en.json's series.add_subseries_to
        title = "Add sub-series to ${state.parentName}",
        onDismiss = { onEvent(AddSubSeriesEvent.Dismissed) },
        panelClass = "sh-dlg",
    ) {
        Field(
            label = "Search series",
            value = state.query,
            onInput = { onEvent(AddSubSeriesEvent.QueryChanged(it)) },
            leading = WebIcon.Search,
            // en.json's series.merge_search_placeholder
            placeholder = "Search series…",
            id = "sub-query",
        )
        Div(attrs = {
            classes("sh-list")
            attr("role", "list")
        }) {
            DomButton(attrs = {
                classes("sh-row", "sh-pinned")
                attr("type", "button")
                onClick { onEvent(AddSubSeriesEvent.NewSeriesStarted) }
            }) {
                Icon(WebIcon.Plus, size = ROW_ICON)
                // en.json's series.new_series
                Span(attrs = { classes("sh-row-n") }) { Text("New series…") }
            }
            if (state.candidates.isEmpty()) {
                // en.json's series.merge_no_matches
                EmptyState(title = "No series match that search.", look = EmptyLook.Inline)
            }
            state.candidates.forEach { candidate ->
                key(candidate.id) { CandidateRow(candidate, onEvent) }
            }
        }
        Div(attrs = { classes("dlg-actions") }) {
            Button(kind = ButtonKind.Secondary, onClick = { onEvent(AddSubSeriesEvent.Dismissed) }) {
                Text("Cancel")
            }
        }
    }
}

/**
 * One series the dialog offers. A series already here stays listed — greyed, `aria-disabled`, its
 * reason ("Already in Cosmere") tied to it with `aria-describedby` — so the list never silently
 * drops what the reader is looking for.
 */
@Composable
private fun CandidateRow(
    candidate: SubSeriesCandidateUi,
    onEvent: (AddSubSeriesEvent) -> Unit,
) {
    val metaId = "sub-meta-${candidate.id}"
    DomButton(attrs = {
        classes("sh-row")
        attr("type", "button")
        attr("role", "listitem")
        attr("aria-describedby", metaId)
        if (!candidate.isSelectable) {
            classes("is-off")
            attr("aria-disabled", "true")
        }
        onClick { if (candidate.isSelectable) onEvent(AddSubSeriesEvent.Chosen(candidate.id)) }
    }) {
        Span(attrs = { classes("sh-row-n") }) { Text(candidate.name) }
        Span(attrs = {
            classes("sh-row-m")
            attr("id", metaId)
        }) { Text(candidateMeta(candidate)) }
    }
}

/**
 * "Top level · 3 books" (en.json's `series.top_level`), "In Discworld · moves it here"
 * (`series.picker_moves_here`), or "Already in Cosmere" (`series.picker_already_in`).
 */
internal fun candidateMeta(candidate: SubSeriesCandidateUi): String =
    when (candidate.placement) {
        SubSeriesPlacement.TOP_LEVEL -> "Top level · ${seriesBookCount(candidate.bookCount)}"
        SubSeriesPlacement.IN_OTHER_PARENT -> "In ${candidate.currentParentName.orEmpty()} · moves it here"
        SubSeriesPlacement.ALREADY_HERE -> "Already in ${candidate.currentParentName.orEmpty()}"
    }

@Composable
private fun MoveConfirmDialog(
    move: PendingSubSeriesMove,
    onEvent: (AddSubSeriesEvent) -> Unit,
) {
    ModalDialog(
        open = true,
        // en.json's series.move_confirm_title
        title = "Move ${move.seriesName} out of ${move.fromParentName} into ${move.toParentName}?",
        onDismiss = { onEvent(AddSubSeriesEvent.MoveCancelled) },
    ) {
        DialogActions(
            // en.json's series.move_confirm_action
            confirmLabel = "Move",
            onConfirm = { onEvent(AddSubSeriesEvent.MoveConfirmed) },
            onDismiss = { onEvent(AddSubSeriesEvent.MoveCancelled) },
        )
    }
}

/**
 * Name a series that does not exist yet — "New series" under Add sub-series, "New parent series"
 * under Move into…. Both refuse a name the library already has and offer that series instead
 * ([useExistingLabel]) when using it is allowed, rather than failing with an error after the press.
 */
@Composable
internal fun NewSeriesNameDialog(
    title: String,
    draft: NewSeriesDraft,
    body: String,
    confirmLabel: String,
    useExistingLabel: String,
    onName: (String) -> Unit,
    onConfirm: () -> Unit,
    onUseExisting: (String) -> Unit,
    onDismiss: () -> Unit,
    idBase: String,
) {
    ModalDialog(open = true, title = title, onDismiss = onDismiss, panelClass = "$idBase-dlg") {
        val existing = draft.existing
        Field(
            // en.json's series.series_name
            label = "Series name",
            value = draft.name,
            onInput = onName,
            id = "$idBase-name",
            autocomplete = "off",
            // en.json's series.name_exists_inline
            errorText = existing?.let { "“${it.name}” already exists." },
        )
        if (existing == null) {
            DialogText(body)
        } else if (existing.isSelectable) {
            Div(attrs = { classes("sh-existing") }) {
                Button(kind = ButtonKind.Secondary, onClick = { onUseExisting(existing.id) }) {
                    Text(useExistingLabel)
                }
            }
        }
        DialogActions(
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            confirmEnabled = draft.canCreate,
        )
    }
}

/** Shared by the dialogs' row glyphs. */
private const val ROW_ICON = 16
