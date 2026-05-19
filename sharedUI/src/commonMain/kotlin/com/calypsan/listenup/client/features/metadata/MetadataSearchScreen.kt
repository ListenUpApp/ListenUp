package com.calypsan.listenup.client.features.metadata

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.domain.repository.MetadataSearchResult
import com.calypsan.listenup.client.presentation.metadata.AudibleRegion
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.client.presentation.metadata.SearchLoadState
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.contributor_audible_region
import listenup.composeapp.generated.resources.contributor_find_on_audible
import listenup.composeapp.generated.resources.common_search
import listenup.composeapp.generated.resources.metadata_title_author_narrator_or_asin

/**
 * Full-screen for searching books on Audible.
 *
 * Shows:
 * - Book context (title being searched for)
 * - Search field (pre-filled with title or ASIN)
 * - Region selector chips
 * - Search results list with covers, titles, authors, narrators
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataSearchScreen(
    state: MetadataUiState.Search,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRegionSelected: (AudibleRegion) -> Unit,
    onResultClick: (MetadataSearchResult) -> Unit,
    onBack: () -> Unit,
) {
    val isSearching = state.loadState is SearchLoadState.InFlight
    val searchError = (state.loadState as? SearchLoadState.Failed)?.message
    val searchResults = (state.loadState as? SearchLoadState.Loaded)?.results.orEmpty()

    Scaffold(
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
            if (state.context.currentTitle.isNotBlank()) {
                Text(
                    text = "Searching for: ${state.context.currentTitle}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(Res.string.common_search)) },
                placeholder = { Text(stringResource(Res.string.metadata_title_author_narrator_or_asin)) },
                trailingIcon = {
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
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            RegionSelector(
                selectedRegion = state.region,
                onRegionSelected = onRegionSelected,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (searchError != null) {
                Text(
                    text = searchError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

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
                    EmptyState(hasSearched = state.query.isNotBlank() && !isSearching)
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
                            MetadataSearchResultItem(
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
 * Region selector chips.
 */
@Composable
private fun RegionSelector(
    selectedRegion: AudibleRegion,
    onRegionSelected: (AudibleRegion) -> Unit,
) {
    Column {
        Text(
            text = stringResource(Res.string.contributor_audible_region),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            AudibleRegion.entries.forEach { region ->
                FilterChip(
                    selected = region == selectedRegion,
                    onClick = { onRegionSelected(region) },
                    label = { Text(region.displayName) },
                )
            }
        }
    }
}

/**
 * Empty state when no results found.
 */
@Composable
private fun EmptyState(hasSearched: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (hasSearched) "No matches found" else "Search Audible",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text =
                    if (hasSearched) {
                        "Try a different search term or region"
                    } else {
                        "Enter a title, author, narrator, or ASIN to search"
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Single search result item showing Audible book metadata.
 */
@Composable
private fun MetadataSearchResultItem(
    result: MetadataSearchResult,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Cover thumbnail
            @Suppress("MagicNumber") // Standard book cover aspect ratio
            AsyncImage(
                model = result.coverUrl,
                contentDescription = null,
                modifier =
                    Modifier
                        .width(56.dp)
                        .aspectRatio(1f / 1.5f)
                        .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Crop,
            )

            Column(modifier = Modifier.weight(1f)) {
                // Title
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // Author
                if (result.authors.isNotEmpty()) {
                    Text(
                        text = "by ${result.authors.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Narrator - KEY DIFFERENTIATOR from local metadata!
                if (result.narrators.isNotEmpty()) {
                    Text(
                        text = "Narrated by ${result.narrators.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Duration and rating
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    val runtime = result.runtimeMinutes
                    if (runtime != null && runtime > 0) {
                        Text(
                            text = formatDuration(runtime),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val rating = result.rating
                    if (rating != null && rating > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "%.1f".format(rating),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Format duration in minutes to human-readable string.
 */
private fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}
