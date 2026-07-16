package com.calypsan.listenup.client.features.contributormetadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import com.calypsan.listenup.client.design.components.EmptyState
import com.calypsan.listenup.client.design.components.ListenUpScaffold
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
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.client.features.metadata.components.RegionSelector
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
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
 * - Search results list
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributorMetadataSearchScreen(
    state: ContributorMetadataUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRegionSelected: (MetadataLocale) -> Unit,
    onResultClick: (MetadataContributorHit) -> Unit,
    onBack: () -> Unit,
) {
    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.contributor_find_on_audible)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
        ) {
            // Context - who we're searching for
            state.currentContributor?.let { contributor ->
                Text(
                    text = stringResource(Res.string.metadata_searching_for, contributor.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            // Search field
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                label = { Text(stringResource(Res.string.contributor_contributor_name)) },
                placeholder = { Text(stringResource(Res.string.contributor_author_or_narrator_name)) },
                trailingIcon = {
                    IconButton(
                        onClick = onSearch,
                        enabled = !state.isSearching && state.searchQuery.isNotBlank(),
                    ) {
                        if (state.isSearching) {
                            ListenUpLoadingIndicatorSmall()
                        } else {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(Res.string.common_search),
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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
                selectedRegion = state.selectedRegion,
                onRegionSelected = onRegionSelected,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Error message
            state.searchError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Results
            when {
                state.isSearching -> {
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

                state.searchResults.isEmpty() && state.searchError == null -> {
                    val hasSearched = state.searchQuery.isNotBlank()
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

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(
                            items = state.searchResults,
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
