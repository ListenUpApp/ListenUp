package com.calypsan.listenup.client.features.metadata

import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.theme.Spacing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.client.design.components.EmptyState
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import kotlin.time.Duration.Companion.minutes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.calypsan.listenup.api.dto.MetadataBook
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.features.metadata.components.RegionSelector
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpSearchField
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.client.presentation.metadata.SearchLoadState
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_narrated_by_value
import listenup.composeapp.generated.resources.metadata_audible_region
import listenup.composeapp.generated.resources.metadata_by_authors
import listenup.composeapp.generated.resources.metadata_enter_a_term_to_search
import listenup.composeapp.generated.resources.metadata_find_on_audible
import listenup.composeapp.generated.resources.metadata_match_metadata
import listenup.composeapp.generated.resources.metadata_matching
import listenup.composeapp.generated.resources.metadata_no_matches_found
import listenup.composeapp.generated.resources.metadata_result_count_match
import listenup.composeapp.generated.resources.metadata_search_audible
import listenup.composeapp.generated.resources.metadata_title_author_narrator_or_asin
import listenup.composeapp.generated.resources.metadata_try_a_different_search_term_or_region

/**
 * Full-screen for searching books on Audible.
 *
 * Shows:
 * - Book context (title being searched for)
 * - Search field (pre-filled with title or ASIN)
 * - Region selector chips
 * - Search results with covers, titles, authors, narrators
 *
 * A phone stacks the controls over one list of candidates. From the medium width the candidates flow
 * into a [GridCells.Adaptive] grid, so a foldable compares two editions at a glance; from the expanded
 * width the controls also move into a side panel, and the query, region and what is being matched stay
 * in view while the grid scrolls beside them. Tapping a candidate still opens its preview as its own
 * screen — the preview needs the whole width for its field sections.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataSearchScreen(
    state: MetadataUiState.Search,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRegionSelected: (MetadataLocale) -> Unit,
    onResultClick: (MetadataBook) -> Unit,
    onBack: () -> Unit,
) {
    val isSearching = state.loadState is SearchLoadState.InFlight
    val searchError = (state.loadState as? SearchLoadState.Failed)?.message
    val searchResults = (state.loadState as? SearchLoadState.Loaded)?.results.orEmpty()
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val controlsBeside = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val resultsInColumns = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    val controls: @Composable () -> Unit = {
        MetadataSearchControls(
            state = state,
            isSearching = isSearching,
            searchError = searchError,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            onRegionSelected = onRegionSelected,
        )
    }
    val results: @Composable ColumnScope.() -> Unit = {
        MetadataSearchResults(
            isSearching = isSearching,
            hasSearched = state.query.isNotBlank() && !isSearching,
            searchError = searchError,
            searchResults = searchResults,
            inColumns = resultsInColumns,
            onResultClick = onResultClick,
        )
    }

    ListenUpScaffold(
        topBar = {
            ColorBlockHero(
                title = stringResource(Res.string.metadata_find_on_audible),
                badgeIcon = Icons.AutoMirrored.Outlined.MenuBook,
                onBack = onBack,
                overline = stringResource(Res.string.metadata_match_metadata),
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
                            .padding(top = 20.dp, bottom = 16.dp),
                ) {
                    controls()
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 20.dp)) {
                    results()
                }
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 18.dp),
            ) {
                Spacer(modifier = Modifier.height(20.dp))
                controls()
                results()
            }
        }
    }
}

/** Width of the expanded layout's search panel — one comfortable phone-width column of controls. */
private val SearchPanelWidth = 360.dp

/** The narrowest a candidate card gets in the results grid before the column count drops. */
private val CandidateMinWidth = 320.dp

/**
 * The search controls: what is being matched, the query, the Audible region, and the last search's
 * error. The same stack heads the phone column and fills the expanded layout's side panel.
 */
@Composable
private fun MetadataSearchControls(
    state: MetadataUiState.Search,
    isSearching: Boolean,
    searchError: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRegionSelected: (MetadataLocale) -> Unit,
) {
    Column {
        if (state.context.currentTitle.isNotBlank()) {
            MatchingContextPill(title = state.context.currentTitle)
            Spacer(modifier = Modifier.height(18.dp))
        }

        ListenUpSearchField(
            value = state.query,
            onValueChange = onQueryChange,
            onSubmit = onSearch,
            placeholder = stringResource(Res.string.metadata_title_author_narrator_or_asin),
            isLoading = isSearching,
        )

        Spacer(modifier = Modifier.height(22.dp))

        Overline(text = stringResource(Res.string.metadata_audible_region))
        Spacer(modifier = Modifier.height(12.dp))
        RegionSelector(
            selectedRegion = state.region,
            onRegionSelected = onRegionSelected,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (searchError != null) {
            Text(
                text = searchError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * The results region: a loading indicator while a search is in flight, the empty state before a
 * search or after a miss, or the candidates — one list, or an adaptive grid when [inColumns].
 */
@Composable
private fun ColumnScope.MetadataSearchResults(
    isSearching: Boolean,
    hasSearched: Boolean,
    searchError: String?,
    searchResults: List<MetadataBook>,
    inColumns: Boolean,
    onResultClick: (MetadataBook) -> Unit,
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
            MetadataSearchEmptyState(hasSearched = hasSearched)
        }

        else -> {
            Overline(text = stringResource(Res.string.metadata_result_count_match, searchResults.size))
            Spacer(modifier = Modifier.height(12.dp))
            if (inColumns) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = CandidateMinWidth),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
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
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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

/** [EmptyState] wired to the metadata-search copy — pre/post-search title and subtitle. */
@Composable
private fun MetadataSearchEmptyState(hasSearched: Boolean) {
    EmptyState(
        title =
            stringResource(
                if (hasSearched) Res.string.metadata_no_matches_found else Res.string.metadata_search_audible,
            ),
        subtitle =
            stringResource(
                if (hasSearched) {
                    Res.string.metadata_try_a_different_search_term_or_region
                } else {
                    Res.string.metadata_enter_a_term_to_search
                },
            ),
    )
}

/** UPPERCASE muted overline label, matching the grouped-section heading idiom. */
@Composable
private fun Overline(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp),
    )
}

/** The "Matching · <title>" context pill that anchors the search to the book being matched. */
@Composable
private fun MatchingContextPill(title: String) {
    Surface(
        // A pill; keep it fully rounded now that the extraLarge token is a bounded 28.dp radius.
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.metadata_matching),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.width(1.dp).height(18.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            ) {}
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Single search result item showing Audible book metadata as an expressive card with a cover,
 * title, author, the differentiating narrator line, and a duration/chapter footnote.
 */
@Composable
private fun MetadataSearchResultItem(
    result: MetadataBook,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cover thumbnail
            @Suppress("MagicNumber") // Standard book cover aspect ratio
            AsyncImage(
                model = result.coverUrl,
                contentDescription = null,
                modifier =
                    Modifier
                        .width(62.dp)
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop,
            )

            Column(modifier = Modifier.weight(1f)) {
                // Title
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // Author
                if (result.authors.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.metadata_by_authors, result.authors.joinToString { it.name }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // Narrator - KEY DIFFERENTIATOR from local metadata!
                if (result.narrators.isNotEmpty()) {
                    Text(
                        text =
                            stringResource(
                                Res.string.book_detail_narrated_by_value,
                                result.narrators.joinToString { it.name },
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Duration
                val runtime = result.runtimeMinutes
                if (runtime != null && runtime > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = DurationFormatter.hoursMinutes(runtime.minutes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Outlined.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
