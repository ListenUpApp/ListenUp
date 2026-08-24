package com.calypsan.listenup.web.features.bookedit

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.Language
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.CheckboxField
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.RelationField
import com.calypsan.listenup.web.design.SelectField
import com.calypsan.listenup.web.design.SelectOption
import com.calypsan.listenup.web.design.TextAreaField
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.client.presentation.bookedit.displayName
import com.calypsan.listenup.web.design.RelationChip
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
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
 * There is no Cancel-confirms-discard dialog. That is a deliberate omission rather than an
 * oversight — see the notes on [EditActions].
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

        // A real <form> spanning the sections AND the actions, so Enter in any field saves — the
        // browser's implicit submission needs the submit button inside the same form as the
        // fields. preventDefault stops the navigation that would otherwise reload the page and
        // discard every unsaved edit on it.
        Form(attrs = {
            classes("edit-body")
            onSubmit { event ->
                event.preventDefault()
                onEvent(BookEditUiEvent.Save)
            }
        }) {
            EditSection("Cover") { CoverField(state, onEvent) }
            EditSection("Details") { CoreFields(state, onEvent) }
            EditSection("People") { ContributorFields(state, onEvent) }
            EditSection("Series") { SeriesFields(state, onEvent) }
            EditSection("Classification") { ClassificationFields(state, onEvent) }
            EditSection("Identifiers") { IdentifierFields(state, onEvent) }
            EditActions(state, onEvent)
        }
    }
}

/**
 * One titled block of form rows.
 *
 * The panel and the row rhythm always travel together — a section that forgot the rhythm would
 * render its fields flush against each other — so they are one thing rather than two lines to
 * remember at five call sites.
 */
@Composable
private fun EditSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Panel(title = title) {
        Div(attrs = { classes("edit-form") }) { content() }
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

/**
 * Genres, tags, moods and collections — four relations, one control.
 *
 * Genres and collections pass no `onCreate`: a genre is system-controlled and a collection is
 * curated, so both may only be chosen. Tags and moods may be invented, and [RelationField] makes
 * that an explicit button rather than something Enter does by accident.
 *
 * Collections render only for an admin, because [BookEditUiState.isAdmin] is the ViewModel's word
 * on whether this reader may attach one — offering the control to everyone else would be a form
 * field whose save is refused.
 */
@Composable
private fun ClassificationFields(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    RelationField(
        label = "Genres",
        attached = state.genres.map { it.toChip() },
        query = state.genreSearchQuery,
        results = state.genreSearchResults.map { it.toChip() },
        onQueryChange = { onEvent(BookEditUiEvent.GenreSearchQueryChanged(it)) },
        onSelect = { chip ->
            state.genreSearchResults.firstOrNull { it.id == chip.id }?.let {
                onEvent(
                    BookEditUiEvent.GenreSelected(it),
                )
            }
        },
        onRemove = { chip ->
            state.genres.firstOrNull { it.id == chip.id }?.let { onEvent(BookEditUiEvent.RemoveGenre(it)) }
        },
        placeholder = "Search genres…",
        id = "edit-genres",
    )
    RelationField(
        label = "Tags",
        attached = state.tags.map { it.toChip() },
        query = state.tagSearchQuery,
        results = state.tagSearchResults.map { it.toChip() },
        loading = state.tagSearchLoading,
        onQueryChange = { onEvent(BookEditUiEvent.TagSearchQueryChanged(it)) },
        onSelect = { chip ->
            state.tagSearchResults.firstOrNull { it.id == chip.id }?.let { onEvent(BookEditUiEvent.TagSelected(it)) }
        },
        onRemove = { chip ->
            state.tags.firstOrNull { it.id == chip.id }?.let { onEvent(BookEditUiEvent.RemoveTag(it)) }
        },
        onCreate = { name -> onEvent(BookEditUiEvent.TagEntered(name)) },
        placeholder = "Search tags…",
        id = "edit-tags",
    )
    RelationField(
        label = "Moods",
        attached = state.moods.map { it.toChip() },
        query = state.moodSearchQuery,
        results = state.moodSearchResults.map { it.toChip() },
        loading = state.moodSearchLoading,
        onQueryChange = { onEvent(BookEditUiEvent.MoodSearchQueryChanged(it)) },
        onSelect = { chip ->
            state.moodSearchResults.firstOrNull { it.id == chip.id }?.let { onEvent(BookEditUiEvent.MoodSelected(it)) }
        },
        onRemove = { chip ->
            state.moods.firstOrNull { it.id == chip.id }?.let { onEvent(BookEditUiEvent.RemoveMood(it)) }
        },
        onCreate = { name -> onEvent(BookEditUiEvent.MoodEntered(name)) },
        placeholder = "Search moods…",
        id = "edit-moods",
    )
    if (state.isAdmin) {
        RelationField(
            label = "Collections",
            attached = state.collections.map { it.toChip() },
            query = state.collectionSearchQuery,
            results = state.collectionSearchResults.map { it.toChip() },
            onQueryChange = { onEvent(BookEditUiEvent.CollectionSearchQueryChanged(it)) },
            onSelect = { chip ->
                state.collectionSearchResults.firstOrNull { it.id == chip.id }?.let {
                    onEvent(
                        BookEditUiEvent.CollectionSelected(it),
                    )
                }
            },
            onRemove = { chip ->
                state.collections.firstOrNull { it.id == chip.id }?.let {
                    onEvent(
                        BookEditUiEvent.RemoveCollection(it),
                    )
                }
            },
            placeholder = "Search collections…",
            id = "edit-collections",
        )
    }
}

/**
 * Contributors, one section per role.
 *
 * A contributor is keyed by NAME rather than id, because a newly-typed one has no id until Save —
 * [com.calypsan.listenup.client.domain.model.EditableContributor]'s `id` is nullable for exactly
 * that reason, and keying on it would make a just-added author unremovable.
 *
 * Only roles already in use are shown, plus whatever the reader opens: a book with an author and a
 * narrator should not present eight empty boxes for adapters and illustrators.
 */
@Composable
private fun ContributorFields(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    state.visibleRoles.sortedBy { it.ordinal }.forEach { role ->
        val attached = state.contributorsForRole(role)
        Div(attrs = { classes("rel-section") }) {
            RelationField(
                label = "${role.displayName}s",
                attached = attached.map { RelationChip(id = it.name, label = it.name) },
                query = state.roleSearchQueries[role].orEmpty(),
                results = state.roleSearchResults[role].orEmpty().map { RelationChip(id = it.id, label = it.name) },
                loading = state.roleSearchLoading[role] == true,
                offline = state.roleOfflineResults[role] == true,
                onQueryChange = { onEvent(BookEditUiEvent.RoleSearchQueryChanged(role, it)) },
                onSelect = { chip ->
                    state.roleSearchResults[role]
                        .orEmpty()
                        .firstOrNull { it.id == chip.id }
                        ?.let { onEvent(BookEditUiEvent.RoleContributorSelected(role, it)) }
                },
                onRemove = { chip ->
                    attached
                        .firstOrNull { it.name == chip.id }
                        ?.let { onEvent(BookEditUiEvent.RemoveContributor(it, role)) }
                },
                onCreate = { name -> onEvent(BookEditUiEvent.RoleContributorEntered(role, name)) },
                placeholder = "Add ${indefiniteArticle(role.displayName)} ${role.displayName.lowercase()}…",
                id = "edit-role-${role.name.lowercase()}",
            )
            Button(attrs = {
                classes("rel-drop")
                attr("type", "button")
                onClick { onEvent(BookEditUiEvent.RemoveRoleSection(role)) }
            }) { Text("Remove all ${role.displayName.lowercase()}s") }
        }
    }

    val hidden = ContributorRole.entries.filterNot { it in state.visibleRoles }
    if (hidden.isNotEmpty()) {
        Div(attrs = { classes("rel-add-roles") }) {
            hidden.forEach { role ->
                Button(attrs = {
                    classes("rel-add-role")
                    attr("type", "button")
                    onClick { onEvent(BookEditUiEvent.AddRoleSection(role)) }
                }) { Text("+ ${role.displayName}") }
            }
        }
    }
}

/**
 * "a" or "an" for [word].
 *
 * Six of the ten contributor roles start with a vowel — author, editor, illustrator, adapter,
 * afterword, introduction — so a hardcoded "a" is wrong more often than it is right.
 */
private fun indefiniteArticle(word: String): String =
    if (word.firstOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

/**
 * Series, with this book's place in each.
 *
 * Keyed by name for the same reason contributors are — a series typed in here has no id until it
 * is saved. The sequence is free text rather than a number box: "1.5" and "0" are both real
 * positions in real series, and so is "Prequel".
 */
@Composable
private fun SeriesFields(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit,
) {
    RelationField(
        label = "Series",
        attached = state.series.map { RelationChip(id = it.name, label = it.name) },
        query = state.seriesSearchQuery,
        results = state.seriesSearchResults.map { RelationChip(id = it.id, label = it.name) },
        loading = state.seriesSearchLoading,
        offline = state.seriesOfflineResult,
        onQueryChange = { onEvent(BookEditUiEvent.SeriesSearchQueryChanged(it)) },
        onSelect = { chip ->
            state.seriesSearchResults
                .firstOrNull { it.id == chip.id }
                ?.let { onEvent(BookEditUiEvent.SeriesSelected(it)) }
        },
        onRemove = { chip ->
            state.series.firstOrNull { it.name == chip.id }?.let { onEvent(BookEditUiEvent.RemoveSeries(it)) }
        },
        onCreate = { name -> onEvent(BookEditUiEvent.SeriesEntered(name)) },
        placeholder = "Search series…",
        id = "edit-series",
    ) { chip ->
        val series = state.series.firstOrNull { it.name == chip.id }
        if (series != null) {
            Input(type = InputType.Text) {
                classes("rel-seq")
                value(series.sequence.orEmpty())
                attr("placeholder", "#")
                attr("aria-label", "Position in ${series.name}")
                onInput { event -> onEvent(BookEditUiEvent.SeriesSequenceChanged(series, event.value)) }
            }
        }
    }
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
        // ⛔ type=button is not decoration. A <button> with no type defaults to SUBMIT, so inside
        // the form this would save the very edits Cancel exists to discard.
        Button(attrs = {
            classes("btn-o")
            attr("type", "button")
            if (state.isSaving) attr("disabled", "")
            onClick { onEvent(BookEditUiEvent.Cancel) }
        }) { Text("Cancel") }
        // No onClick: submitting the form is what saves, for click and Enter alike.
        Button(attrs = {
            classes("btn-c")
            attr("type", "submit")
            if (state.isSaving) attr("disabled", "")
        }) { Text(if (state.isSaving) "Saving…" else "Save") }
    }
}

/**
 * Built once: the shared list is a couple of hundred entries and rebuilding it per keystroke
 * would re-render every option on every edit to any field on the page.
 */
private val LANGUAGE_OPTIONS: List<SelectOption> =
    Language.getAllLanguages().map { SelectOption(value = it.code, label = it.name) }
