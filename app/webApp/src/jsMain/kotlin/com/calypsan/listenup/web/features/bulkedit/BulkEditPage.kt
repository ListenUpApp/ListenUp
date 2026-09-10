package com.calypsan.listenup.web.features.bulkedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditPreviewRow
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.web.design.Field
import com.calypsan.listenup.web.design.FormSection
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.RelationChip
import com.calypsan.listenup.web.design.RelationField
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** Everything the form needs to offer, gathered so the page's own signature stays readable. */
class BulkEditCatalog(
    val genres: List<Genre>,
    val tags: List<Tag>,
    val moods: List<Mood>,
    val seriesMatches: List<SeriesSearchResult>,
    val contributorMatches: List<ContributorSearchResult>,
)

/** Every change the form can report. One object, because the page takes twelve of them otherwise. */
@Suppress("LongParameterList")
class BulkEditActions(
    val onSeriesQuery: (String) -> Unit,
    val onContributorQuery: (String) -> Unit,
    val onPublisher: (String) -> Unit,
    val onYear: (Int?) -> Unit,
    val onLanguage: (String) -> Unit,
    val onSeries: (BookSeriesInput?) -> Unit,
    val onContributors: (List<BookContributorInput>) -> Unit,
    val onGenres: (List<BookGenreInput>) -> Unit,
    val onTags: (List<String>) -> Unit,
    val onMoods: (List<String>) -> Unit,
    val onApply: () -> Unit,
    val onLeave: () -> Unit,
)

/**
 * Edit many books at once.
 *
 * **A bulk edit has no undo, so this screen's entire job is to be understood before Apply rather
 * than after.** Three things do that work. Every field carries a sentence saying what leaving it —
 * or filling it — will do. A panel says what applying would change, per instruction, with the
 * count and the proportion. And the button names the number it will change rather than saying
 * "Apply".
 *
 * **A field you do not touch is never written.** The dimmed text in a publishing field is what
 * those books already say — a placeholder, not a value — and adding to a relation never removes
 * what a book already has.
 */
@Composable
fun BulkEditPage(
    state: BulkEditUiState,
    catalog: BulkEditCatalog,
    actions: BulkEditActions,
    notice: String? = null,
) {
    Div(attrs = { classes("bke") }) {
        when (state) {
            BulkEditUiState.Loading -> Div(attrs = { classes("skel", "bke-skel") })
            is BulkEditUiState.Editing -> EditingContent(state, catalog, actions, notice)
        }
    }
}

@Composable
private fun EditingContent(
    state: BulkEditUiState.Editing,
    catalog: BulkEditCatalog,
    actions: BulkEditActions,
    notice: String?,
) {
    Div(attrs = { classes("bke-head") }) {
        Div(attrs = { classes("bke-titles") }) {
            Span(attrs = { classes("bke-eyebrow") }) { Text("Library · ${state.bookCount} selected") }
            H1(attrs = { classes("bke-t") }) {
                Text(if (state.bookCount == 1) "Edit 1 book" else "Edit ${state.bookCount} books")
            }
        }
        Button(attrs = {
            classes(BTN_SECONDARY)
            attr(ATTR_TYPE, VALUE_BUTTON)
            onClick { actions.onLeave() }
        }) { Text("Cancel") }
    }

    // ⛔ Named, not swallowed. A book deleted from another device between the grid and this screen
    // silently drops out of the selection, and editing thirty-nine books after choosing forty
    // without saying so is the kind of quiet difference nobody forgives.
    notLoadedNote(state)?.let {
        P(attrs = {
            classes("bke-warn")
            attr("role", "status")
        }) { Text(it) }
    }

    notice?.let {
        P(attrs = {
            classes("bke-notice")
            attr("role", "alert")
        }) { Text(it) }
    }

    FormSection(title = "Publishing") {
        P(attrs = { classes("bke-note") }) {
            Text(
                "A field you don’t touch is never written. The dimmed text is what these books " +
                    "already say — a placeholder, not a value.",
            )
        }
        PublishingFields(state, actions)
    }

    FormSection(title = "Credits") {
        P(attrs = { classes("bke-note") }) {
            Text("Adding never removes — every book keeps the series and the people it already has.")
        }
        CreditFields(state, catalog, actions)
    }

    FormSection(title = "Classification") {
        P(attrs = { classes("bke-note") }) {
            Text("Adding never removes — every book keeps the genres, tags and moods it already has.")
        }
        ClassificationFields(state, catalog, actions)
    }

    FormSection(title = "What will change") { PreviewPanel(state.preview, state.bookCount) }

    Div(attrs = { classes("bke-apply") }) {
        Button(attrs = {
            classes("btn-c")
            attr(ATTR_TYPE, VALUE_BUTTON)
            disabledWhen(state.isApplying || !state.canApply)
            onClick { actions.onApply() }
        }) { Text(applyLabel(state.changedBookCount, state.isApplying)) }
    }
}

@Composable
private fun PublishingFields(
    state: BulkEditUiState.Editing,
    actions: BulkEditActions,
) {
    ConsequenceField(
        label = "Publisher",
        value = state.publisherInput,
        placeholder = state.sharedPublisher ?: MIXED,
        consequence = state.consequenceOf<BulkEdit.SetPublisher>(state.sharedPublisher),
        id = "bke-publisher",
        onInput = actions.onPublisher,
        onClear = { actions.onPublisher("") },
    )
    ConsequenceField(
        label = "Publication year",
        value = state.yearInput,
        placeholder = state.sharedPublishYear?.toString() ?: MIXED,
        consequence = state.consequenceOf<BulkEdit.SetPublishYear>(state.sharedPublishYear?.toString()),
        id = "bke-year",
        onInput = { actions.onYear(it.toIntOrNull()) },
        onClear = { actions.onYear(null) },
    )
    ConsequenceField(
        label = "Language",
        value = state.languageInput,
        placeholder = state.sharedLanguage ?: MIXED,
        consequence = state.consequenceOf<BulkEdit.SetLanguage>(state.sharedLanguage),
        id = "bke-language",
        onInput = actions.onLanguage,
        onClear = { actions.onLanguage("") },
    )
}

/**
 * One field, the sentence that says what leaving it — or not — will do, and the way back.
 *
 * ⛔ The clear button is not a nicety. On this screen a stray keystroke arms an instruction over
 * forty books, and the only thing that disarms it is emptying the field — which a reader will not
 * think to do unless there is something that visibly undoes what they just did.
 */
@Composable
private fun ConsequenceField(
    label: String,
    value: String,
    placeholder: String,
    consequence: FieldConsequence,
    id: String,
    onInput: (String) -> Unit,
    onClear: () -> Unit,
) {
    Div(attrs = {
        classes("bke-field")
        if (consequence.writes) classes("armed")
    }) {
        Div(attrs = { classes("bke-field-row") }) {
            Field(label = label, value = value, onInput = onInput, placeholder = placeholder, id = id)
            if (value.isNotEmpty()) {
                Button(attrs = {
                    classes("bke-clear")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    attr("aria-label", "Clear $label")
                    onClick { onClear() }
                }) { Icon(WebIcon.X, size = SMALL_ICON) }
            }
        }
        P(attrs = { classes("bke-consequence") }) { Text(consequence.text) }
    }
}

@Composable
private fun CreditFields(
    state: BulkEditUiState.Editing,
    catalog: BulkEditCatalog,
    actions: BulkEditActions,
) {
    var seriesQuery by remember { mutableStateOf("") }
    var contributorQuery by remember { mutableStateOf("") }

    // At most one series: the ViewModel's instruction carries a single membership, so a second
    // chip would be a promise the edit cannot keep.
    RelationField(
        label = "Add to series",
        attached = state.seriesInput?.let { listOf(RelationChip(it.name, it.name)) }.orEmpty(),
        query = seriesQuery,
        results = catalog.seriesMatches.map { RelationChip(it.name, it.name) },
        onQueryChange = {
            seriesQuery = it
            actions.onSeriesQuery(it)
        },
        onSelect = { chip ->
            actions.onSeries(BookSeriesInput(name = chip.label))
            seriesQuery = ""
        },
        onRemove = { actions.onSeries(null) },
        placeholder = "Search series",
        id = "bke-series",
    )
    RelationField(
        label = "Add contributors",
        attached = state.contributorInput.map { RelationChip(it.name, it.name) },
        query = contributorQuery,
        results = catalog.contributorMatches.map { RelationChip(it.name, it.name) },
        onQueryChange = {
            contributorQuery = it
            actions.onContributorQuery(it)
        },
        onSelect = { chip ->
            actions.onContributors(
                state.contributorInput +
                    BookContributorInput(name = chip.label, role = AUTHOR_ROLE, position = state.contributorInput.size),
            )
            contributorQuery = ""
        },
        onRemove = { chip -> actions.onContributors(state.contributorInput.filterNot { it.name == chip.id }) },
        placeholder = "Search people",
        id = "bke-contributors",
    )
}

/**
 * Genres, tags and moods.
 *
 * ⛔ Picked from what the library already holds, never invented. Minting a tag forty books at a time
 * is how a library ends up with `found-family`, `Found Family` and `found family` as three separate
 * things — so no field here passes an `onCreate`.
 */
@Composable
private fun ClassificationFields(
    state: BulkEditUiState.Editing,
    catalog: BulkEditCatalog,
    actions: BulkEditActions,
) {
    var genreQuery by remember { mutableStateOf("") }
    var tagQuery by remember { mutableStateOf("") }
    var moodQuery by remember { mutableStateOf("") }

    val genreById = catalog.genres.associateBy { it.id }
    RelationField(
        label = "Add genres",
        attached =
            state.genreInput.mapNotNull { input ->
                genreById[input.genreId.value]?.let { RelationChip(it.id, it.name) }
            },
        query = genreQuery,
        results =
            catalog.genres
                .filterNot { genre -> state.genreInput.any { it.genreId.value == genre.id } }
                .matching(genreQuery) { it.name }
                .map { RelationChip(it.id, it.name) },
        onQueryChange = { genreQuery = it },
        onSelect = { chip ->
            actions.onGenres(state.genreInput + BookGenreInput(genreId = GenreId(chip.id)))
            genreQuery = ""
        },
        onRemove = { chip -> actions.onGenres(state.genreInput.filterNot { it.genreId.value == chip.id }) },
        placeholder = "Search genres",
        id = "bke-genres",
    )
    NameRelation(
        label = "Add tags",
        chosen = state.tagInput,
        available = catalog.tags.map { it.name },
        query = tagQuery,
        onQuery = { tagQuery = it },
        onChange = actions.onTags,
        placeholder = "Search tags",
        id = "bke-tags",
    )
    NameRelation(
        label = "Add moods",
        chosen = state.moodInput,
        available = catalog.moods.map { it.name },
        query = moodQuery,
        onQuery = { moodQuery = it },
        onChange = actions.onMoods,
        placeholder = "Search moods",
        id = "bke-moods",
    )
}

/** A relation whose values are plain names — tags and moods, which the ViewModel takes as strings. */
@Composable
private fun NameRelation(
    label: String,
    chosen: List<String>,
    available: List<String>,
    query: String,
    onQuery: (String) -> Unit,
    onChange: (List<String>) -> Unit,
    placeholder: String,
    id: String,
) {
    RelationField(
        label = label,
        attached = chosen.map { RelationChip(it, it) },
        query = query,
        results = available.filterNot { it in chosen }.matching(query) { it }.map { RelationChip(it, it) },
        onQueryChange = onQuery,
        onSelect = { chip ->
            onChange(chosen + chip.label)
            onQuery("")
        },
        onRemove = { chip -> onChange(chosen.filterNot { it == chip.id }) },
        placeholder = placeholder,
        id = id,
    )
}

/**
 * What applying would actually do, per instruction.
 *
 * ⛔ Each row is named as well as counted. Three bare counts — "12 of 40", "40 of 40", "8 of 40" —
 * are honest and unusable: the one instruction the reader wants to reconsider is not identifiable
 * among them. Each row also names the books it *leaves alone*, because the gap between twelve and
 * forty is otherwise the part a reader assumes is a bug.
 */
@Composable
private fun PreviewPanel(
    rows: List<BulkEditPreviewRow>,
    bookCount: Int,
) {
    if (rows.isEmpty()) {
        // Named rather than blank: an empty panel is indistinguishable from a broken one, and this
        // is the first thing every user of this screen sees.
        Div(attrs = { classes("bke-empty") }) {
            H2 { Text("Nothing to change yet") }
            P { Text("Type into a field above. Every book keeps the values you don’t touch.") }
        }
        return
    }

    Div(attrs = { classes("bke-rows") }) {
        rows.forEach { row ->
            // A row that changes nothing is dimmed rather than hidden; a vanished row would read
            // as a lost edit.
            Div(attrs = {
                classes("bke-row")
                if (row.affectedCount == 0) classes("off")
            }) {
                Div(attrs = { classes("bke-row-head") }) {
                    Span(attrs = { classes("bke-row-l") }) { Text(labelOf(row.edit)) }
                    Span(attrs = { classes("bke-row-c") }) { Text(affectsText(row.affectedCount, bookCount)) }
                }
                // The count again, in a form nobody has to count: the eye catches "less than a
                // third" before it parses "12 of 40".
                Div(attrs = {
                    classes("bke-bar")
                    attr("role", "presentation")
                }) {
                    Div(attrs = {
                        classes("bke-bar-fill")
                        style { property("width", "${(proportionOf(row, bookCount) * PERCENT).toInt()}%") }
                    })
                }
                leftAloneNote(row.edit, row.affectedCount, bookCount)?.let {
                    P(attrs = { classes("bke-row-note") }) { Text(it) }
                }
            }
        }
    }
}

/** Case-insensitive contains, or everything when the query is blank. */
private fun <T> List<T>.matching(
    query: String,
    label: (T) -> String,
): List<T> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return this
    return filter { label(it).contains(trimmed, ignoreCase = true) }
}

/** What an untouched publishing field shows when the books do not agree. */
private const val MIXED = "Multiple values"

/** Bulk-added contributors are authors; a per-person role picker is the single-book editor's job. */
private const val AUTHOR_ROLE = "author"

private const val ATTR_TYPE = "type"

private const val BTN_SECONDARY = "btn-o"

private const val VALUE_BUTTON = "button"

private const val SMALL_ICON = 14

private const val PERCENT = 100
