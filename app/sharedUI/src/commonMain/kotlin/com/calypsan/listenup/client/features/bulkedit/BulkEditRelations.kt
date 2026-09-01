package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.client.design.components.AutocompleteResultItem
import com.calypsan.listenup.client.design.components.ListenUpAutocompleteField
import com.calypsan.listenup.client.domain.bulkedit.BulkEdit
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.SeriesSearchResult
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.SeriesId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_role_adapter
import listenup.composeapp.generated.resources.book_role_afterword
import listenup.composeapp.generated.resources.book_role_author
import listenup.composeapp.generated.resources.book_role_editor
import listenup.composeapp.generated.resources.book_role_foreword
import listenup.composeapp.generated.resources.book_role_illustrator
import listenup.composeapp.generated.resources.book_role_introduction
import listenup.composeapp.generated.resources.book_role_narrator
import listenup.composeapp.generated.resources.book_role_producer
import listenup.composeapp.generated.resources.book_role_translator
import listenup.composeapp.generated.resources.bulk_edit_contributors
import listenup.composeapp.generated.resources.bulk_edit_genres
import listenup.composeapp.generated.resources.bulk_edit_moods
import listenup.composeapp.generated.resources.bulk_edit_relation_untouched
import listenup.composeapp.generated.resources.bulk_edit_role
import listenup.composeapp.generated.resources.bulk_edit_series
import listenup.composeapp.generated.resources.bulk_edit_tags
import listenup.composeapp.generated.resources.bulk_edit_remove_from_edit
import listenup.composeapp.generated.resources.bulk_edit_search_contributors
import listenup.composeapp.generated.resources.bulk_edit_search_genres
import listenup.composeapp.generated.resources.bulk_edit_search_moods
import listenup.composeapp.generated.resources.bulk_edit_search_no_matches
import listenup.composeapp.generated.resources.bulk_edit_search_series
import listenup.composeapp.generated.resources.bulk_edit_search_tags
import org.jetbrains.compose.resources.stringResource

/** Gap between a relation field's label, its picker, and the chips it has collected. */
private val RelationGap = 10.dp

/** The chip's close glyph is a target, not an illustration — but a small one. */
private val ChipIconSize = 16.dp

/** Gap between one relation field and the next, matching the publishing card's field rhythm. */
private val FieldBlockGap = 20.dp

/** The glyph beside a match in the dropdown, sized as the shared component's own default is. */
private val MatchIconSize = 24.dp

/**
 * One thing a relation field has collected, ready to draw and to take back off.
 *
 * @property key what makes it unique within its field, so two people with the same name in
 *   different roles are two chips rather than one.
 * @property label what the chip reads.
 */
internal data class RelationChip(
    val key: String,
    val label: String,
)

/**
 * A field that collects existing things from the library rather than accepting typed text.
 *
 * The publishing fields **replace** a value; these **add** to what each book already carries. That
 * difference is the whole reason they look different: there is no value the selection can be said
 * to share, so there is no placeholder to show and nothing to warn about overwriting. What there
 * is, is the same promise the text fields make — a field nobody touches writes to nothing — and the
 * same consequence line saying so.
 *
 * Only things already in the library can be picked. A bulk edit that could mint a genre forty books
 * at a time is how a library ends up with `found-family`, `Found Family` and `found family` as three
 * different things; and for tags and moods a brand-new one needs the server, so offering it here
 * would be a field that works only while online on a screen whose whole point is that it does not
 * lie about what it will do.
 *
 * @param label what this field collects, e.g. "Add genres".
 * @param placeholder what the search box says while it is empty, e.g. "Search genres" — a different
 *   sentence from [label], because a box repeating the heading above it tells the reader nothing.
 * @param query what has been typed into the picker.
 * @param onQueryChange the picker's text changed.
 * @param matches what the library offers for that text, already filtered by the caller.
 * @param matchLabel how one match reads in the dropdown.
 * @param matchSupporting a second line for a match, when there is something worth saying.
 * @param matchIcon the glyph beside a match. The field's own, because the shared component's
 *   default is a person, and a genre drawn as a person is a small untruth in a list of them.
 * @param onPick a match was chosen.
 * @param chosen what has been collected so far.
 * @param onRemove a chip was taken back off.
 * @param consequence what this field will do to the selection, in a sentence.
 * @param enabled false while an apply is in flight.
 * @param modifier Modifier for the field.
 */
@Composable
@Suppress("LongParameterList")
@OptIn(ExperimentalLayoutApi::class)
internal fun <T> BulkRelationField(
    label: String,
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    matches: List<T>,
    matchLabel: (T) -> String,
    matchSupporting: (T) -> String?,
    matchIcon: ImageVector,
    onPick: (T) -> Unit,
    chosen: List<RelationChip>,
    onRemove: (RelationChip) -> Unit,
    consequence: FieldConsequence,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RelationGap)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            // An armed field is unmistakable here for the same reason it is in the publishing card:
            // the whole safety of the screen is reading a touched field apart from an untouched one.
            color =
                if (chosen.isEmpty()) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
        )
        if (chosen.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                chosen.forEach { chip ->
                    InputChip(
                        selected = true,
                        onClick = { if (enabled) onRemove(chip) },
                        label = { Text(chip.label) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(Res.string.bulk_edit_remove_from_edit, chip.label),
                                modifier = Modifier.size(ChipIconSize),
                            )
                        },
                        enabled = enabled,
                        colors = InputChipDefaults.inputChipColors(),
                    )
                }
            }
        }
        ListenUpAutocompleteField(
            value = query,
            onValueChange = onQueryChange,
            results = matches,
            onResultSelected = onPick,
            // Enter does not create: there is nothing to create here, and a field that swallowed a
            // typed name silently would be the one lie this screen cannot tell.
            onSubmit = {},
            resultContent = { match ->
                AutocompleteResultItem(
                    name = matchLabel(match),
                    subtitle = matchSupporting(match),
                    onClick = { onPick(match) },
                    leadingIcon = {
                        Icon(
                            imageVector = matchIcon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MatchIconSize),
                        )
                    },
                )
            },
            placeholder = placeholder,
            emptyResultsContent = {
                Text(
                    text = stringResource(Res.string.bulk_edit_search_no_matches),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            },
        )
        ConsequenceLine(consequence)
    }
}

/** What an untouched relation field says: nothing is added unless something is picked. */
@Composable
internal fun untouchedRelationConsequence(): FieldConsequence =
    FieldConsequence(
        text = stringResource(Res.string.bulk_edit_relation_untouched),
        icon = Icons.Outlined.LockOpen,
        writes = false,
    )

/**
 * What the relation pickers have to offer: the library's own things, and nothing else.
 *
 * Genres, tags and moods arrive whole from Room and are narrowed here as the user types; series and
 * contributors are searched instead, because a library holds more of them than a dropdown can show.
 */
internal data class BulkEditOffers(
    val genres: List<Genre> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val moods: List<Mood> = emptyList(),
    val seriesMatches: List<SeriesSearchResult> = emptyList(),
    val contributorMatches: List<ContributorSearchResult> = emptyList(),
)

/**
 * What the form calls when a field changes.
 *
 * One bag rather than fourteen parameters. Every one defaults to doing nothing so a test can render
 * the screen while naming only the callback it is about to assert on.
 */
internal data class BulkEditFormActions(
    val onPublisherChange: (String) -> Unit = {},
    val onYearChange: (Int?) -> Unit = {},
    val onLanguageChange: (String) -> Unit = {},
    val onSeriesQueryChange: (String) -> Unit = {},
    val onSeriesChange: (BookSeriesInput?) -> Unit = {},
    val onContributorQueryChange: (String) -> Unit = {},
    val onContributorsChange: (List<BookContributorInput>) -> Unit = {},
    val onGenresChange: (List<BookGenreInput>) -> Unit = {},
    val onTagsChange: (List<String>) -> Unit = {},
    val onMoodsChange: (List<String>) -> Unit = {},
)

/**
 * Series and people: the two relations that are searched rather than listed.
 *
 * One series, because [com.calypsan.listenup.client.domain.bulkedit.BulkEdit.AddToSeries] carries
 * one and the planner drops its position — a single sequence across forty books would make every one
 * of them Book 1, so this field cannot offer a number and does not pretend to.
 */
@Composable
internal fun BulkEditCredits(
    state: BulkEditUiState.Editing,
    offers: BulkEditOffers,
    actions: BulkEditFormActions,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.isApplying
    var seriesQuery by remember { mutableStateOf("") }
    var contributorQuery by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ContributorRole.AUTHOR) }
    val credited = state.contributorInput

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FieldBlockGap)) {
        BulkRelationField(
            label = stringResource(Res.string.bulk_edit_series),
            placeholder = stringResource(Res.string.bulk_edit_search_series),
            query = seriesQuery,
            onQueryChange = {
                seriesQuery = it
                actions.onSeriesQueryChange(it)
            },
            matches = offers.seriesMatches,
            matchLabel = { it.name },
            matchSupporting = { null },
            matchIcon = Icons.AutoMirrored.Outlined.MenuBook,
            onPick = { match ->
                actions.onSeriesChange(BookSeriesInput(id = SeriesId(match.id), name = match.name))
                seriesQuery = ""
                actions.onSeriesQueryChange("")
            },
            chosen = state.seriesInput?.let { listOf(RelationChip(key = it.name, label = it.name)) }.orEmpty(),
            onRemove = { actions.onSeriesChange(null) },
            consequence =
                state.armedConsequenceOf<BulkEdit.AddToSeries>() ?: untouchedRelationConsequence(),
            enabled = enabled,
        )
        Column(verticalArrangement = Arrangement.spacedBy(RelationGap)) {
            RolePicker(role = role, onRoleChange = { role = it }, enabled = enabled)
            BulkRelationField(
                label = stringResource(Res.string.bulk_edit_contributors),
                placeholder = stringResource(Res.string.bulk_edit_search_contributors),
                query = contributorQuery,
                onQueryChange = {
                    contributorQuery = it
                    actions.onContributorQueryChange(it)
                },
                matches = offers.contributorMatches,
                matchLabel = { it.name },
                matchSupporting = { null },
                matchIcon = Icons.Outlined.Person,
                onPick = { match ->
                    // Position is a per-book ordinal the planner renumbers, so any value here is
                    // arbitrary; zero says "let the planner decide" without pretending otherwise.
                    val addition =
                        BookContributorInput(
                            id = ContributorId(match.id),
                            name = match.name,
                            role = role.apiValue,
                            position = 0,
                        )
                    val already = credited.any { it.name == match.name && it.role == role.apiValue }
                    if (!already) actions.onContributorsChange(credited + addition)
                    contributorQuery = ""
                    actions.onContributorQueryChange("")
                },
                chosen =
                    credited.map { RelationChip(key = "${it.name}/${it.role}", label = "${it.name} · ${it.role}") },
                onRemove = { chip ->
                    actions.onContributorsChange(credited.filterNot { "${it.name}/${it.role}" == chip.key })
                },
                consequence =
                    state.armedConsequenceOf<BulkEdit.AddContributors>() ?: untouchedRelationConsequence(),
                enabled = enabled,
            )
        }
    }
}

/**
 * Genres, tags and moods: the three the library hands over whole.
 *
 * Narrowed here rather than by a query to the server, for the same reason search is: the lists are
 * already in Room, so the picker works with the network off.
 */
@Composable
internal fun BulkEditClassification(
    state: BulkEditUiState.Editing,
    offers: BulkEditOffers,
    actions: BulkEditFormActions,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.isApplying
    var genreQuery by remember { mutableStateOf("") }
    var tagQuery by remember { mutableStateOf("") }
    var moodQuery by remember { mutableStateOf("") }

    val chosenGenreIds = state.genreInput.map { it.genreId.value }.toSet()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FieldBlockGap)) {
        BulkRelationField(
            label = stringResource(Res.string.bulk_edit_genres),
            placeholder = stringResource(Res.string.bulk_edit_search_genres),
            query = genreQuery,
            onQueryChange = { genreQuery = it },
            matches = offers.genres.matching(genreQuery) { it.name }.filterNot { it.id in chosenGenreIds },
            matchLabel = { it.name },
            matchSupporting = { it.parentPath },
            matchIcon = Icons.Outlined.Category,
            onPick = { genre ->
                actions.onGenresChange(state.genreInput + BookGenreInput(genreId = GenreId(genre.id)))
                genreQuery = ""
            },
            chosen =
                state.genreInput.map { input ->
                    val name = offers.genres.firstOrNull { it.id == input.genreId.value }?.name
                    RelationChip(key = input.genreId.value, label = name ?: input.genreId.value)
                },
            onRemove = { chip ->
                actions.onGenresChange(state.genreInput.filterNot { it.genreId.value == chip.key })
            },
            consequence = state.armedConsequenceOf<BulkEdit.AddGenres>() ?: untouchedRelationConsequence(),
            enabled = enabled,
        )
        NameRelationField(
            label = stringResource(Res.string.bulk_edit_tags),
            placeholder = stringResource(Res.string.bulk_edit_search_tags),
            query = tagQuery,
            onQueryChange = { tagQuery = it },
            offered = offers.tags.map { it.name },
            chosen = state.tagInput,
            onChange = actions.onTagsChange,
            onQueryConsumed = { tagQuery = "" },
            consequence = state.armedConsequenceOf<BulkEdit.AddTags>() ?: untouchedRelationConsequence(),
            matchIcon = Icons.AutoMirrored.Outlined.Label,
            enabled = enabled,
        )
        NameRelationField(
            label = stringResource(Res.string.bulk_edit_moods),
            placeholder = stringResource(Res.string.bulk_edit_search_moods),
            query = moodQuery,
            onQueryChange = { moodQuery = it },
            offered = offers.moods.map { it.name },
            chosen = state.moodInput,
            onChange = actions.onMoodsChange,
            onQueryConsumed = { moodQuery = "" },
            consequence = state.armedConsequenceOf<BulkEdit.AddMoods>() ?: untouchedRelationConsequence(),
            matchIcon = Icons.Outlined.Mood,
            enabled = enabled,
        )
    }
}

/**
 * A relation identified by its display name rather than an id — which is what tags and moods are.
 *
 * The name, never the slug: the repositories slugify server-side, so a slug passed in its place
 * becomes the display name of anything newly created — a tag literally called `found-family`.
 */
@Composable
@Suppress("LongParameterList")
private fun NameRelationField(
    label: String,
    placeholder: String,
    query: String,
    onQueryChange: (String) -> Unit,
    offered: List<String>,
    chosen: List<String>,
    onChange: (List<String>) -> Unit,
    onQueryConsumed: () -> Unit,
    consequence: FieldConsequence,
    matchIcon: ImageVector,
    enabled: Boolean,
) {
    BulkRelationField(
        label = label,
        placeholder = placeholder,
        query = query,
        onQueryChange = onQueryChange,
        matches = offered.matching(query) { it }.filterNot { it in chosen },
        matchLabel = { it },
        matchSupporting = { null },
        matchIcon = matchIcon,
        onPick = { name ->
            onChange(chosen + name)
            onQueryConsumed()
        },
        chosen = chosen.map { RelationChip(key = it, label = it) },
        onRemove = { chip -> onChange(chosen.filterNot { it == chip.key }) },
        consequence = consequence,
        enabled = enabled,
    )
}

/**
 * The role the next person picked will be credited in.
 *
 * One role at a time rather than one per chip, because picking the role and then the person is how
 * the sentence reads — "add these three as narrators" — and because a role chosen per chip would
 * have to be changed after the fact, on a screen where every control is already a live instruction.
 */
@Composable
private fun RolePicker(
    role: ContributorRole,
    onRoleChange: (ContributorRole) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text("${stringResource(Res.string.bulk_edit_role)}: ${roleLabel(role)}")
            Icon(imageVector = Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ContributorRole.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(roleLabel(option)) },
                    onClick = {
                        onRoleChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The reader-facing name of a credit role. */
@Composable
private fun roleLabel(role: ContributorRole): String =
    stringResource(
        when (role) {
            ContributorRole.AUTHOR -> Res.string.book_role_author
            ContributorRole.NARRATOR -> Res.string.book_role_narrator
            ContributorRole.EDITOR -> Res.string.book_role_editor
            ContributorRole.TRANSLATOR -> Res.string.book_role_translator
            ContributorRole.FOREWORD -> Res.string.book_role_foreword
            ContributorRole.INTRODUCTION -> Res.string.book_role_introduction
            ContributorRole.AFTERWORD -> Res.string.book_role_afterword
            ContributorRole.PRODUCER -> Res.string.book_role_producer
            ContributorRole.ADAPTER -> Res.string.book_role_adapter
            ContributorRole.ILLUSTRATOR -> Res.string.book_role_illustrator
        },
    )

/**
 * The offered things that match what has been typed. Nothing, until something has been.
 *
 * An empty query offering the whole library would leave a dropdown hanging open under every field
 * from the moment the screen appears, pushing the fields below it off the page — and the box says
 * "Search", so a list that arrives before the search does is not what it promised.
 */
private fun <T> List<T>.matching(
    query: String,
    name: (T) -> String,
): List<T> {
    val trimmed = query.trim()
    return if (trimmed.isEmpty()) emptyList() else filter { name(it).contains(trimmed, ignoreCase = true) }
}
