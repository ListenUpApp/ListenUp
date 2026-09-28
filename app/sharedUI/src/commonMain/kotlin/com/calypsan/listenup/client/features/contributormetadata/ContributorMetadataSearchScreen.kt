package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.EmptyState
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.client.features.metadata.components.RegionSelector
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorSearchLoadState
import com.calypsan.listenup.api.metadata.MetadataLocale
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.contributor_audible_region
import listenup.composeapp.generated.resources.contributor_author_or_narrator_name
import listenup.composeapp.generated.resources.contributor_contributor_name
import listenup.composeapp.generated.resources.metadata_searching_for
import listenup.composeapp.generated.resources.contributor_find_on_audible
import listenup.composeapp.generated.resources.common_search

/**
 * Full-screen for searching contributors on Audible.
 *
 * Shows:
 * - Contributor name as context
 * - Search field (pre-filled with contributor name)
 * - Region selector chips
 * - Search results
 *
 * A phone stacks the controls over one list of candidates. From the medium width the candidates flow
 * into a [GridCells.Adaptive] grid; from the expanded width the controls also move into a side panel,
 * so the name and region stay in view while the grid scrolls beside them. Tapping a candidate opens
 * its preview as its own screen, as it does on a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributorMetadataSearchScreen(
    state: ContributorMetadataUiState.Search,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRegionSelected: (MetadataLocale) -> Unit,
    onResultClick: (MetadataContributorHit) -> Unit,
    onBack: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val isSearching = state.loadState is ContributorSearchLoadState.InFlight
    val searchError = (state.loadState as? ContributorSearchLoadState.Failed)?.message
    val searchResults = (state.loadState as? ContributorSearchLoadState.Loaded)?.results.orEmpty()
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val controlsBeside = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val resultsInColumns = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val controls: @Composable () -> Unit = {
        ContributorSearchControls(
            state = state,
            isSearching = isSearching,
            searchError = searchError,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onRegionSelected = onRegionSelected,
        )
    }
    val results: @Composable ColumnScope.() -> Unit = {
        ContributorSearchResultsSection(
            isSearching = isSearching,
            hasSearched = state.loadState is ContributorSearchLoadState.Loaded,
            searchError = searchError,
            searchResults = searchResults,
            inColumns = resultsInColumns,
            onResultClick = onResultClick,
        )
    }

    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(Res.string.contributor_find_on_audible),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptics.press()
                            onBack()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        if (controlsBeside) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = Spacing.screenMargin),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            ) {
                Column(
                    modifier =
                        Modifier
                            .width(SearchPanelWidth)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 16.dp),
                ) {
                    controls()
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    results()
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = Spacing.lg),
            ) {
                controls()
                results()
            }
        }
    }
}

/** Width of the expanded layout's search panel — one comfortable phone-width column of controls. */
private val SearchPanelWidth = 360.dp

/** The narrowest a candidate card gets in the results grid before the column count drops. */
private val CandidateMinWidth = 280.dp

/**
 * The search controls: who is being matched, the name field, the Audible region, and the last
 * search's error. The same stack heads the phone column and fills the expanded layout's side panel.
 */
@Composable
private fun ContributorSearchControls(
    state: ContributorMetadataUiState.Search,
    isSearching: Boolean,
    searchError: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRegionSelected: (MetadataLocale) -> Unit,
) {
    Column {
        // Context - who we're searching for
        state.context.current?.let { contributor ->
            Text(
                text = stringResource(Res.string.metadata_searching_for, contributor.name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        // Search field
        ListenUpTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = stringResource(Res.string.contributor_contributor_name),
            placeholder = stringResource(Res.string.contributor_author_or_narrator_name),
            trailingContent = {
                IconButton(
                    onClick = onSearch,
                    enabled = !isSearching && state.query.isNotBlank(),
                ) {
                    if (isSearching) {
                        ListenUpLoadingIndicatorSmall()
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(Res.string.common_search),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            // Keep the pre-migration corner radius (the OutlinedTextField default).
            shape = OutlinedTextFieldDefaults.shape,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Region selector
        Text(
            text = stringResource(Res.string.contributor_audible_region),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        RegionSelector(
            selectedRegion = state.region,
            onRegionSelected = onRegionSelected,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Error message
        searchError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * The results region of [ContributorMetadataSearchScreen]: a loading spinner while a search is
 * in flight, an [EmptyState] for "not searched yet" / "no matches", or the results — one list, or an
 * adaptive grid when [inColumns] — whichever applies to the current [ContributorSearchLoadState].
 */
@Composable
private fun ColumnScope.ContributorSearchResultsSection(
    isSearching: Boolean,
    hasSearched: Boolean,
    searchError: String?,
    searchResults: List<MetadataContributorHit>,
    inColumns: Boolean,
    onResultClick: (MetadataContributorHit) -> Unit,
) {
    when {
        isSearching -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                ListenUpLoadingIndicator()
            }
        }

        searchResults.isEmpty() && searchError == null -> {
            EmptyState(
                title = if (hasSearched) "No matches found" else "Search Audible",
                subtitle =
                    if (hasSearched) {
                        "Try a different name or region"
                    } else {
                        "Enter a name to search for contributors on Audible"
                    },
            )
        }

        inColumns -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = CandidateMinWidth),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(
                    items = searchResults,
                    key = { it.asin },
                ) { result ->
                    ContributorSearchResultItem(
                        result = result,
                        onClick = { onResultClick(result) },
                    )
                }
            }
        }

        else -> {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(
                    items = searchResults,
                    key = { it.asin },
                ) { result ->
                    ContributorSearchResultItem(
                        result = result,
                        onClick = { onResultClick(result) },
                    )
                }
            }
        }
    }
}

/**
 * Single contributor search result item.
 *
 * [MetadataContributorHit] carries only [asin] and [name]; no imageUrl or
 * description is available at the search-results tier (the full profile is
 * fetched on selection via [ContributorMetadataViewModel.selectCandidate]).
 */
@Composable
private fun ContributorSearchResultItem(
    result: MetadataContributorHit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Placeholder icon — hit data carries no image; full profile loads on selection
            Surface(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = result.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
