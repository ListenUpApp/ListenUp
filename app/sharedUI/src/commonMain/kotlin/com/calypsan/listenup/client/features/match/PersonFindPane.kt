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
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.match.InLibraryUi
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_edit_by_hand
import listenup.composeapp.generated.resources.match_no_profiles_body
import listenup.composeapp.generated.resources.match_no_profiles_title
import listenup.composeapp.generated.resources.match_search_person_label
import listenup.composeapp.generated.resources.match_title
import org.jetbrains.compose.resources.stringResource

/** Covers the Your-library strip overlaps, and by how much. */
private val STRIP_COVER = 40.dp
private val STRIP_OVERLAP = 12.dp

/**
 * The Find step for a person: the search field, the Your-library strip — what they did here, every role — then
 * Strong match and Maybe, or "No source has a profile" with Edit by hand ([onEditByHand]), or the failure that
 * explains why there is nothing. There is no role to choose: Find looks for the person in every role. A running
 * search keeps the previous results under a wavy progress line.
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
    val name = state.header?.name

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.match_title),
                subtitle = name,
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
            item(key = "search") {
                MatchSearchField(
                    query = state.query,
                    label = stringResource(Res.string.match_search_person_label),
                    onSearch = actions::search,
                )
            }
            state.inLibrary?.let { library ->
                item(key = "library") {
                    LibraryStrip(
                        library = library,
                        steps =
                            if (results != null && name != null) {
                                personStepsLine(results.steps, name)
                            } else {
                                null
                            },
                    )
                }
            }
            if (state is PersonFindUiState.Searching) item(key = "searching") { SearchingIndicator() }
            if (state is PersonFindUiState.Failed) {
                item(key = "failure") { FindFailureContent(failure = state.failure, onRetry = actions::retry) }
            }
            if (state is PersonFindUiState.NoProfiles) {
                item(key = "no-profiles") { NoProfiles(onEditByHand = onEditByHand) }
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

/** The person's books here, from Room: up to three covers, "Narrated 5 of your books · Wrote 1: …", the steps. */
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
                steps?.let { stepsText ->
                    Text(
                        text = stepsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun stripWidth(covers: Int) = STRIP_COVER + (STRIP_COVER - STRIP_OVERLAP) * (covers - 1)

/** "No source has a profile for this person", what to do instead, and Edit by hand. */
@Composable
private fun NoProfiles(onEditByHand: () -> Unit) {
    FailureMessage(
        icon = Icons.Outlined.PersonSearch,
        title = stringResource(Res.string.match_no_profiles_title),
        body = stringResource(Res.string.match_no_profiles_body),
    ) {
        ListenUpButton(
            text = stringResource(Res.string.match_edit_by_hand),
            onClick = onEditByHand,
            fillMaxWidth = false,
        )
    }
}
