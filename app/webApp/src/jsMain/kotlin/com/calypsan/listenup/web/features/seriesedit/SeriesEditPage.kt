package com.calypsan.listenup.web.features.seriesedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.seriesedit.MAX_MERGE_CANDIDATES
import com.calypsan.listenup.client.presentation.seriesedit.SeriesCandidate
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiEvent
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.web.design.CoverPickerField
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.TextAreaField
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import com.calypsan.listenup.core.SeriesId
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Series Edit — what a series is called, what it is about, and what it is really.
 *
 * Pure in [state]; the store wiring lives one level up. Every change leaves as a
 * [SeriesEditUiEvent], the same shape Book Edit and Contributor Edit use.
 *
 * **The merge is the reason this page is not just another form.** Folding this series into another
 * moves every book across and deletes this one, and the server cannot put it back. So the picker
 * asks for a selection and then a second, deliberate press — unlike the contributor picker, where
 * a mis-pick is undone by splitting the alias back out. The dialog says how many books will move
 * and that it cannot be undone, because those are the two facts the decision turns on.
 */
@Composable
fun SeriesEditPage(
    state: SeriesEditUiState,
    mergeCandidates: List<SeriesCandidate>,
    onEvent: (SeriesEditUiEvent) -> Unit,
    onMergeQuery: (String) -> Unit,
) {
    Div(attrs = { classes("sed") }) {
        if (state.isLoading) {
            Div(attrs = { classes("skel", "sed-skel") })
            return@Div
        }

        H1(attrs = { classes("sed-title") }) { Text(state.name.ifBlank { "Series" }) }

        state.error?.let { message ->
            Div(attrs = { classes("sed-err") }) {
                P(attrs = {
                    classes("sed-err-t")
                    attr("role", "alert")
                }) { Text(message) }
                Button(attrs = {
                    classes("sed-err-x")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    attr("aria-label", "Dismiss")
                    onClick { onEvent(SeriesEditUiEvent.ErrorDismissed) }
                }) { Icon(WebIcon.X, size = SMALL_ICON) }
            }
        }

        // A real <form> over the fields AND the actions, so Enter in any field saves — the
        // browser's implicit submission needs the submit button inside the same form.
        Form(attrs = {
            classes("edit-body")
            onSubmit { event ->
                event.preventDefault()
                onEvent(SeriesEditUiEvent.SaveClicked)
            }
        }) {
            FormSection(title = "Cover") { CoverSection(state, onEvent) }
            FormSection(title = "Identity") { IdentityFields(state, onEvent) }
            FormSection(title = "This series") { MergeSection(state, onEvent) }
            EditActions(state, onEvent)
        }

        if (state.mergeDialogVisible) {
            MergeDialog(
                query = state.mergeQuery,
                candidates = mergeCandidates,
                bookCount = state.bookCount,
                onQuery = onMergeQuery,
                onConfirm = { onEvent(SeriesEditUiEvent.MergeInto(it)) },
                onDismiss = { onEvent(SeriesEditUiEvent.MergeDialogDismissed) },
            )
        }
    }
}

/**
 * The series' own artwork, and the way to replace it.
 *
 * ⛔ `/api/v1/series/{id}/cover` and nothing else. Series Detail deliberately shows the *first
 * book's* cover, because a series with no artwork of its own is the common case there and a
 * gradient beats a 404. Here the question is the opposite one — whether this series has artwork —
 * so borrowing a book's would answer it wrongly, and every reader would think the job was done.
 */
@Composable
private fun CoverSection(
    state: SeriesEditUiState,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    var coverFailed by remember(state.seriesId) { mutableStateOf(false) }

    CoverPickerField(
        pendingBytes = state.pendingCoverData,
        isUploading = state.isUploadingCover,
        inputId = "sed-cover-input",
        onPicked = { bytes, filename ->
            onEvent(SeriesEditUiEvent.CoverSelected(imageData = bytes, filename = filename))
        },
        // Discards the staged pick, which is all the ViewModel's CoverRemoved does — it never
        // deletes artwork the server already holds, so a "Remove cover" label would be a promise
        // nothing here keeps.
        onDiscardPick = { onEvent(SeriesEditUiEvent.CoverRemoved) },
    ) {
        if (state.coverPath != null && !coverFailed) {
            Img(
                src = seriesCoverUrl(state.seriesId),
                alt = state.name,
                attrs = {
                    classes("sed-cover")
                    attr("decoding", "async")
                    addEventListener("error") { coverFailed = true }
                },
            )
        } else {
            Div(attrs = { classes("sed-cover", "sed-cover-none") }) {
                Icon(WebIcon.Book, size = COVER_ICON)
                Span { Text("No cover yet") }
            }
        }
    }
}

@Composable
private fun IdentityFields(
    state: SeriesEditUiState,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    Field(
        label = "Name",
        value = state.name,
        onInput = { onEvent(SeriesEditUiEvent.NameChanged(it)) },
        id = "sed-name",
    )
    TextAreaField(
        label = "Description",
        value = state.descriptionText,
        onInput = { onEvent(SeriesEditUiEvent.DescriptionChanged(it)) },
        id = "sed-description",
    )
}

/** What this series holds, and the one destructive thing that can be done to it. */
@Composable
private fun MergeSection(
    state: SeriesEditUiState,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    P(attrs = { classes("sed-hint") }) { Text("${bookCountLabel(state.bookCount)} in this series.") }
    Div(attrs = { classes("sed-merge-act") }) {
        Button(attrs = {
            classes(BTN_SECONDARY, "sed-merge")
            attr(ATTR_TYPE, VALUE_BUTTON)
            disabledWhen(state.mergeInProgress)
            onClick { onEvent(SeriesEditUiEvent.MergeDialogOpened) }
        }) { Text(if (state.mergeInProgress) "Merging…" else "Merge into another series") }
    }
}

/**
 * Pick a series to fold this one into.
 *
 * Select, then confirm — two gestures, because the merge deletes this series and moves every book
 * out of it, and there is no un-merge. The list is capped at [MAX_MERGE_CANDIDATES] and opens
 * unfiltered, so a full page is the signal that more exist behind a search: a silently truncated
 * list reads as a complete one, and "it isn't in the list" is how the wrong series gets picked.
 */
@Composable
private fun MergeDialog(
    query: String,
    candidates: List<SeriesCandidate>,
    bookCount: Int,
    onQuery: (String) -> Unit,
    onConfirm: (SeriesId) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<SeriesId?>(null) }

    ModalDialog(open = true, title = "Merge into another series", onDismiss = onDismiss) {
        P(attrs = { classes("dlg-p") }) {
            Text("This series is folded into the one you pick: ${bookCountLabel(bookCount)} move across.")
        }
        P(attrs = { classes("sed-warn") }) { Text("This cannot be undone.") }
        Field(
            label = "Search series",
            value = query,
            onInput = onQuery,
            id = "sed-merge-query",
        )
        if (candidates.size >= MAX_MERGE_CANDIDATES) {
            // Above the list, not below it: the list scrolls, so a notice underneath is out of
            // sight exactly when the list is long enough to need one.
            P(attrs = { classes("sed-trunc") }) {
                Text("Showing the first $MAX_MERGE_CANDIDATES. Search to narrow them down.")
            }
        }
        Div(attrs = { classes("sed-results") }) {
            if (candidates.isEmpty()) {
                P(attrs = { classes(NONE) }) { Text("Nothing matched \"$query\".") }
            } else {
                candidates.forEach { candidate ->
                    Button(attrs = {
                        classes("sed-result")
                        if (selected == candidate.id) classes("on")
                        attr(ATTR_TYPE, VALUE_BUTTON)
                        attr("aria-pressed", (selected == candidate.id).toString())
                        onClick { selected = candidate.id }
                    }) { Span(attrs = { classes("sed-result-n") }) { Text(candidate.displayName) } }
                }
            }
        }
        Div(attrs = { classes("dlg-actions") }) {
            Button(attrs = {
                classes("btn")
                attr(ATTR_TYPE, VALUE_BUTTON)
                onClick { onDismiss() }
            }) { Text("Cancel") }
            Button(attrs = {
                classes("btn-d")
                attr(ATTR_TYPE, VALUE_BUTTON)
                disabledWhen(selected == null)
                onClick { selected?.let(onConfirm) }
            }) { Text("Merge") }
        }
    }
}

/**
 * Save and Cancel.
 *
 * Save is disabled until something changes, for the reason it is everywhere else: an enabled Save
 * on an untouched form invites a press that reports success for a change nobody made.
 */
@Composable
private fun EditActions(
    state: SeriesEditUiState,
    onEvent: (SeriesEditUiEvent) -> Unit,
) {
    Div(attrs = { classes("edit-actions") }) {
        // ⛔ type=button: a <button> with no type inside a form defaults to SUBMIT, so this would
        // save the very edits Cancel exists to discard.
        Button(attrs = {
            classes(BTN_SECONDARY)
            attr(ATTR_TYPE, VALUE_BUTTON)
            disabledWhen(state.isSaving)
            onClick { onEvent(SeriesEditUiEvent.CancelClicked) }
        }) { Text("Cancel") }
        // No onClick: submitting the form is what saves, for click and Enter alike.
        Button(attrs = {
            classes("btn-c")
            attr(ATTR_TYPE, "submit")
            disabledWhen(state.isSaving || !state.hasChanges)
        }) { Text(if (state.isSaving) "Saving…" else "Save changes") }
    }
}

/** Where the server serves a series' own artwork from. 404s for every series that has none. */
internal fun seriesCoverUrl(seriesId: String): String = "/api/v1/series/$seriesId/cover"

private fun bookCountLabel(count: Int): String = if (count == 1) "1 book" else "$count books"

private const val ATTR_TYPE = "type"

private const val BTN_SECONDARY = "btn-o"

private const val NONE = "sed-none"

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 16

private const val COVER_ICON = 28
