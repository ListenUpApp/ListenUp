package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ButtonGroupChoice
import com.calypsan.listenup.client.design.components.ConnectedSelectButtonGroup
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.match.CoverageNote
import com.calypsan.listenup.client.presentation.match.InLibraryUi
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_as_author
import listenup.composeapp.generated.resources.match_as_narrator
import listenup.composeapp.generated.resources.match_edit_by_hand
import listenup.composeapp.generated.resources.match_match_as
import listenup.composeapp.generated.resources.match_no_profiles_author_title
import listenup.composeapp.generated.resources.match_no_profiles_body
import listenup.composeapp.generated.resources.match_no_profiles_narrator_title
import listenup.composeapp.generated.resources.match_search_narrator_label
import listenup.composeapp.generated.resources.match_search_person_label
import listenup.composeapp.generated.resources.match_title
import org.jetbrains.compose.resources.stringResource

/** Covers the Your-library strip overlaps, and by how much. */
private val STRIP_COVER = 40.dp
private val STRIP_OVERLAP = 12.dp

/**
 * The Find step for a person: As author | As narrator, the search field, the coverage note when the first source
 * has no profiles for the role, the Your-library strip, then Strong match and Maybe — or "No source has a
 * profile" with Edit by hand ([onEditByHand]), or the failure that explains why there is nothing. A running
 * search keeps this role's previous results under a wavy progress line.
 */
@Composable
internal fun PersonFindPane(
    state: PersonFindUiState,
    highlightPicked: Boolean,
    actions: PersonMatchActions,
    onBack: () -> Unit,
    onEditByHand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val results =
        when (state) {
            is PersonFindUiState.Results -> state
            is PersonFindUiState.Searching -> state.previous
            is PersonFindUiState.NoProfiles, is PersonFindUiState.Failed -> null
        }
    val coverageNote = if (state is PersonFindUiState.NoProfiles) state.coverageNote else results?.coverageNote
    val name = state.header?.name

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.match_title),
                subtitle = name?.let { personSubtitle(it, state.role) },
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding =
                PaddingValues(
                    start = Spacing.screenMargin,
                    end = Spacing.screenMargin,
                    bottom = Spacing.xl,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "role") { RoleSwitch(role = state.role, onSwitch = actions::switchRole) }
            item(key = "search") {
                MatchSearchField(
                    query = state.query,
                    label =
                        stringResource(
                            if (state.role == ContributorRole.NARRATOR) {
                                Res.string.match_search_narrator_label
                            } else {
                                Res.string.match_search_person_label
                            },
                        ),
                    onSearch = actions::search,
                )
            }
            coverageNote?.let { note -> item(key = "coverage") { CoverageNoteCard(note, state.role) } }
            state.inLibrary?.let { library ->
                item(key = "library") {
                    LibraryStrip(
                        library = library,
                        steps = results?.let { found -> name?.let { personStepsLine(found.steps, it, state.role) } },
                    )
                }
            }
            if (state is PersonFindUiState.Searching) item(key = "searching") { SearchingIndicator() }
            if (state is PersonFindUiState.Failed) {
                item(key = "failure") { FindFailureContent(failure = state.failure, onRetry = actions::retry) }
            }
            if (state is PersonFindUiState.NoProfiles) {
                item(key = "no-profiles") { NoProfiles(role = state.role, onEditByHand = onEditByHand) }
            }
            results?.let { found ->
                personGroups(
                    results = found,
                    highlightPicked = highlightPicked,
                    onPick = { actions.pick(it.key) },
                    onRetrySources = actions::retry,
                )
            }
        }
    }
}

/** As author | As narrator, one connected group; choosing the other role runs (or restores) its Find. */
@Composable
private fun RoleSwitch(
    role: ContributorRole,
    onSwitch: (ContributorRole) -> Unit,
) {
    val haptics = LocalHaptics.current
    ConnectedSelectButtonGroup(
        choices =
            listOf(
                ButtonGroupChoice(ContributorRole.AUTHOR, stringResource(Res.string.match_as_author)),
                ButtonGroupChoice(ContributorRole.NARRATOR, stringResource(Res.string.match_as_narrator)),
            ),
        selected = role,
        onSelect = { chosen ->
            if (chosen != role) {
                haptics.toggle(on = true)
                onSwitch(chosen)
            }
        },
        groupLabel = stringResource(Res.string.match_match_as),
    )
}

/** "Atlas has no narrator profiles, so this search uses Beacon." */
@Composable
private fun CoverageNoteCard(
    note: CoverageNote,
    role: ContributorRole,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm + Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(note.text(role), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** The person's books here, from Room: up to three covers, "Wrote 3 books in your library: …", and the steps. */
@Composable
private fun LibraryStrip(
    library: InLibraryUi,
    steps: String?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md).semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (library.covers.isNotEmpty()) {
                Box(modifier = Modifier.size(width = stripWidth(library.covers.size), height = STRIP_COVER)) {
                    library.covers.forEachIndexed { index, cover ->
                        BookCoverImage(
                            bookId = cover.bookId,
                            coverPath = cover.coverPath,
                            coverHash = cover.coverHash,
                            contentDescription = null,
                            title = cover.title,
                            modifier = Modifier.offset(x = (STRIP_COVER - STRIP_OVERLAP) * index).size(STRIP_COVER),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(library.sentence(), style = MaterialTheme.typography.bodyMedium)
                steps?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun stripWidth(covers: Int) = STRIP_COVER + (STRIP_COVER - STRIP_OVERLAP) * (covers - 1)

/** "No source has a profile for this narrator", what to do instead, and Edit by hand. */
@Composable
private fun NoProfiles(
    role: ContributorRole,
    onEditByHand: () -> Unit,
) {
    FailureMessage(
        icon = Icons.Outlined.PersonSearch,
        title =
            stringResource(
                if (role == ContributorRole.NARRATOR) {
                    Res.string.match_no_profiles_narrator_title
                } else {
                    Res.string.match_no_profiles_author_title
                },
            ),
        body = stringResource(Res.string.match_no_profiles_body),
    ) {
        ListenUpButton(
            text = stringResource(Res.string.match_edit_by_hand),
            onClick = onEditByHand,
            fillMaxWidth = false,
        )
    }
}
