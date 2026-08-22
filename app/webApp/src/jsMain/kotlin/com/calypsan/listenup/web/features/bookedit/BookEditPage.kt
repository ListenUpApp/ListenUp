package com.calypsan.listenup.web.features.bookedit

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.Language
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.CheckboxField
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.SelectField
import com.calypsan.listenup.web.design.SelectOption
import com.calypsan.listenup.web.design.TextAreaField
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Book Edit — the core metadata of one book, over the shared
 * [com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel]'s state.
 *
 * Pure in [state], exactly as Book Detail is: every value rendered here comes from the ViewModel
 * and every change leaves as a [BookEditUiEvent]. Nothing is fetched or held at this layer, which
 * is what lets a spec drive any state — mid-save, errored, half-filled — deterministically.
 *
 * **Scope is deliberately the plain fields.** Contributors, series, genres and tags are edited
 * through search-and-pick affordances that each need a real picker; the ViewModel already carries
 * their state and events, so they arrive as additions here rather than as a rewrite. Leaving them
 * out is not the same as dropping them: an unedited field keeps whatever the book already had,
 * because Save sends the ViewModel's whole state, not this form's subset.
 *
 * There is no Cancel-confirms-discard dialog, and no cover control. Both are deliberate omissions
 * rather than oversights — see the notes on [EditActions].
 */
@Composable
fun BookEditPage(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBook: () -> Unit,
) {
    Div(attrs = { classes("bd") }) {
        Breadcrumb(listOf("Library", state.title.ifBlank { "Book" }, "Edit")) { index ->
            if (index == 0) onOpenLibrary() else onOpenBook()
        }

        if (state.isLoading) {
            Div(attrs = { classes("empty") }) { P { Text("Loading…") } }
            return@Div
        }

        state.error?.let { message ->
            // Dismissible rather than fatal: the form still holds the reader's edits, and throwing
            // the page away over a failed save would throw those away with it.
            Div(attrs = { classes("edit-error") }) {
                P { Text(message) }
                Button(attrs = {
                    classes("btn-o")
                    onClick { onEvent(BookEditUiEvent.DismissError) }
                }) { Text("Dismiss") }
            }
        }

        Panel(title = "Details") {
            Div(attrs = { classes("edit-form") }) { CoreFields(state, onEvent) }
        }
        Panel(title = "Identifiers") {
            Div(attrs = { classes("edit-form") }) { IdentifierFields(state, onEvent) }
        }
        EditActions(state, onEvent)
    }
}

/** Title through publisher — what a reader actually corrects on an imported book. */
@Composable
private fun CoreFields(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    Div(attrs = { classes("edit-grid") }) {
        Field(
            label = "Title",
            value = state.title,
            onInput = { onEvent(BookEditUiEvent.TitleChanged(it)) },
            id = "edit-title",
        )
        Field(
            label = "Sort title",
            value = state.sortTitle,
            onInput = { onEvent(BookEditUiEvent.SortTitleChanged(it)) },
            placeholder = "Ignored leading articles, e.g. \"Martian, The\"",
            id = "edit-sort-title",
        )
    }
    Field(
        label = "Subtitle",
        value = state.subtitle,
        onInput = { onEvent(BookEditUiEvent.SubtitleChanged(it)) },
        id = "edit-subtitle",
    )
    TextAreaField(
        label = "Description",
        value = state.description,
        onInput = { onEvent(BookEditUiEvent.DescriptionChanged(it)) },
        id = "edit-description",
    )
    Div(attrs = { classes("edit-grid") }) {
        Field(
            label = "Publisher",
            value = state.publisher,
            onInput = { onEvent(BookEditUiEvent.PublisherChanged(it)) },
            id = "edit-publisher",
        )
        Field(
            label = "Publish year",
            value = state.publishYear,
            onInput = { onEvent(BookEditUiEvent.PublishYearChanged(it)) },
            id = "edit-publish-year",
        )
    }
    SelectField(
        label = "Language",
        value = state.language,
        options = LANGUAGE_OPTIONS,
        onSelect = { onEvent(BookEditUiEvent.LanguageChanged(it)) },
        emptyLabel = "Not recorded",
        id = "edit-language",
    )
}

/** ISBN, ASIN and the abridged flag — rarely touched, so they sit below the fold. */
@Composable
private fun IdentifierFields(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    Div(attrs = { classes("edit-grid") }) {
        Field(
            label = "ISBN",
            value = state.isbn,
            onInput = { onEvent(BookEditUiEvent.IsbnChanged(it)) },
            id = "edit-isbn",
        )
        Field(
            label = "ASIN",
            value = state.asin,
            onInput = { onEvent(BookEditUiEvent.AsinChanged(it)) },
            id = "edit-asin",
        )
    }
    CheckboxField(
        label = "Abridged",
        checked = state.abridged,
        onChange = { onEvent(BookEditUiEvent.AbridgedChanged(it)) },
        id = "edit-abridged",
    )
}

/**
 * Save and Cancel.
 *
 * Save is disabled while a save is in flight and says so, because the alternative — an enabled
 * button that quietly does nothing on the second press — is the shape that produces duplicate
 * writes and reports success for a request nobody made.
 *
 * Cancel does not ask "discard changes?". The ViewModel treats Cancel as leave-without-saving, and
 * inventing a confirmation here would put a decision in the web UI that neither native client
 * makes — the kind of per-platform divergence that turns one product into three.
 */
@Composable
private fun EditActions(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    Div(attrs = { classes("edit-actions") }) {
        Button(attrs = {
            classes("btn-o")
            if (state.isSaving) attr("disabled", "")
            onClick { onEvent(BookEditUiEvent.Cancel) }
        }) { Text("Cancel") }
        Button(attrs = {
            classes("btn-c")
            if (state.isSaving) attr("disabled", "")
            onClick { onEvent(BookEditUiEvent.Save) }
        }) { Text(if (state.isSaving) "Saving…" else "Save") }
    }
}

/**
 * Built once: the shared list is a couple of hundred entries and rebuilding it per keystroke
 * would re-render every option on every edit to any field on the page.
 */
private val LANGUAGE_OPTIONS: List<SelectOption> =
    Language.getAllLanguages().map { SelectOption(value = it.code, label = it.name) }
