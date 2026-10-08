package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.RegionUi
import com.calypsan.listenup.client.presentation.match.YourCopyUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_clear_search
import listenup.composeapp.generated.resources.match_just_this_search
import listenup.composeapp.generated.resources.match_search_label
import listenup.composeapp.generated.resources.match_searching
import listenup.composeapp.generated.resources.match_store_button
import listenup.composeapp.generated.resources.match_title
import listenup.composeapp.generated.resources.match_your_copy
import org.jetbrains.compose.resources.stringResource

/** Test tag of the Find pane, so a two-pane test can find both panes. */
internal const val FIND_PANE_TAG = "match-find-pane"

/**
 * The Find step: the search field seeded with the book's title, the store control, the Your copy strip, then
 * Strong match and Maybe groups — or the failure that explains why there are none. A running search keeps the
 * previous results on screen under a wavy progress line.
 */
@Composable
internal fun FindPane(
    state: FindUiState,
    bookId: String,
    highlightPicked: Boolean,
    actions: BookMatchActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var comparing by remember { mutableStateOf<CandidateUi?>(null) }
    val results =
        when (state) {
            is FindUiState.Results -> state
            is FindUiState.Searching -> state.previous
            is FindUiState.Failed -> null
        }
    val region =
        when (state) {
            is FindUiState.Results -> state.region
            is FindUiState.Searching -> state.previous?.region
            is FindUiState.Failed -> state.region
        }
    val query =
        when (state) {
            is FindUiState.Results -> state.query
            is FindUiState.Searching -> state.query
            is FindUiState.Failed -> state.query
        }

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ListenUpTopAppBar(
                title = stringResource(Res.string.match_title),
                subtitle = state.yourCopy?.title,
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
                    query = query,
                    label = stringResource(Res.string.match_search_label),
                    onSearch = actions::search,
                )
            }
            region?.let { item(key = "store") { StoreButton(region = it, onChoose = actions::chooseStore) } }
            val copy = state.yourCopy
            if (copy != null) {
                item(
                    key = "your-copy",
                ) { YourCopyStrip(bookId = bookId, copy = copy, steps = results?.let { stepsLine(it.steps) }) }
            }
            if (state is FindUiState.Searching) item(key = "searching") { SearchingIndicator() }
            if (state is FindUiState.Failed) {
                item(key = "failure") {
                    FindFailureContent(
                        failure = state.failure,
                        onRetry = actions::retry,
                        onChooseStore = actions::chooseStore,
                        onSearchByTitle = actions::searchByTitle,
                    )
                }
            }
            results?.let { found ->
                candidateGroups(
                    results = found,
                    highlightPicked = highlightPicked,
                    onPick = { actions.pick(it.key) },
                    onCompare = { comparing = it },
                    onRetrySources = actions::retry,
                )
            }
        }
    }

    comparing?.let { candidate ->
        CompareSheet(
            candidate = candidate,
            yourCopy = state.yourCopy,
            region = region,
            onReview = {
                comparing = null
                actions.pick(candidate.key)
            },
            onDismiss = { comparing = null },
        )
    }
}

/** The M3 search field, seeded with what Find searched and named [label]; submitting searches again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatchSearchField(
    query: String,
    label: String,
    onSearch: (String) -> Unit,
) {
    val textState = rememberTextFieldState(query)
    val searchBarState = rememberSearchBarState()
    // Follow what Find actually searched (the title arrives from Room after the first frame) without
    // clobbering a search the person is still typing: the query only changes on submit.
    LaunchedEffect(query) {
        if (textState.text.toString() != query) textState.setTextAndPlaceCursorAtEnd(query)
    }
    SearchBar(
        state = searchBarState,
        modifier = Modifier.fillMaxWidth(),
        inputField = {
            SearchBarDefaults.InputField(
                textFieldState = textState,
                searchBarState = searchBarState,
                onSearch = onSearch,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                placeholder = { Text(label) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (textState.text.isNotEmpty()) {
                        IconButton(onClick = { textState.setTextAndPlaceCursorAtEnd("") }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(Res.string.match_clear_search),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        },
    )
}

/** "<source> store: United States" — a menu of the source's stores, for this search only. */
@Composable
private fun StoreButton(
    region: RegionUi,
    onChoose: (MetadataLocale) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHaptics.current
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(Res.string.match_store_button, region.source.label, region.region.displayName))
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.padding(start = Spacing.xs),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                text = stringResource(Res.string.match_just_this_search),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
            region.choices.forEach { choice ->
                val current = choice.region == region.region.region
                DropdownMenuItem(
                    text = { Text(choice.displayName) },
                    trailingIcon =
                        if (current) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    onClick = {
                        expanded = false
                        if (!current) {
                            haptics.press()
                            onChoose(choice)
                        }
                    },
                )
            }
        }
    }
}

/** Your copy: cover, "16h 10m · Ray Porter · 36 chapters", and what Find started from. */
@Composable
private fun YourCopyStrip(
    bookId: String,
    copy: YourCopyUi,
    steps: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCoverImage(
            bookId = bookId,
            coverPath = copy.coverPath,
            coverHash = copy.coverHash,
            contentDescription = null,
            title = copy.title,
            author = copy.authors.firstOrNull(),
            modifier = Modifier.size(56.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.match_your_copy),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            val facts = copy.factsLine()
            if (facts.isNotEmpty()) Text(facts, style = MaterialTheme.typography.bodyMedium)
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

/** The wavy line under the field while Find runs, announced as "Searching…". */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SearchingIndicator() {
    val searching = stringResource(Res.string.match_searching)
    Column(
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = searching
            },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            searching,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
