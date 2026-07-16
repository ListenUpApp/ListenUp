package com.calypsan.listenup.client.features.storyworld

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.EntityTile
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.ListenUpSearchField
import com.calypsan.listenup.client.features.storyworld.components.HubEventRow
import com.calypsan.listenup.client.features.storyworld.components.HubKindCard
import com.calypsan.listenup.client.features.storyworld.components.RecentEntitiesRail
import com.calypsan.listenup.client.features.storyworld.components.StorySoFarSeamCard
import com.calypsan.listenup.client.features.storyworld.components.WorldEmptyState
import com.calypsan.listenup.client.features.storyworld.components.WorldSpoilerBanner
import com.calypsan.listenup.client.presentation.storyworld.EntityCard
import com.calypsan.listenup.client.presentation.storyworld.StoryWorldHubUiState
import com.calypsan.listenup.client.presentation.storyworld.StoryWorldHubViewModel
import com.calypsan.listenup.client.presentation.storyworld.WorldRef
import com.calypsan.listenup.api.sync.EntityKind
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.story_world_hidden_count
import listenup.composeapp.generated.resources.story_world_hub_title
import listenup.composeapp.generated.resources.story_world_latest_events
import listenup.composeapp.generated.resources.story_world_search_placeholder
import listenup.composeapp.generated.resources.story_world_show_anyway

/**
 * The Story World hub — a series' or standalone book's entry point into its encyclopedia:
 * entity-kind tiles, a recently-updated rail, and the latest log entries. Adapts by width:
 * narrow is one scrolling column; wide splits into a fixed-width left column (seam card, kind
 * cards, spoiler banner) beside a flexible right column (recently updated, latest events).
 */
@Composable
fun StoryWorldHubScreen(
    seriesId: String?,
    bookId: String?,
    onBackClick: () -> Unit,
    onEntityClick: (String) -> Unit,
    onKindClick: (EntityKind) -> Unit,
    viewModel: StoryWorldHubViewModel = koinViewModel(),
) {
    LaunchedEffect(seriesId, bookId) { viewModel.load(WorldRef(seriesId, bookId)) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    ListenUpScaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                StoryWorldHubUiState.Idle, StoryWorldHubUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is StoryWorldHubUiState.Ready -> {
                    val wide =
                        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
                            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                        )
                    if (wide) {
                        WideStoryWorldHubContent(
                            state = current,
                            onBackClick = onBackClick,
                            onEntityClick = onEntityClick,
                            onKindClick = onKindClick,
                            onSearchQueryChange = viewModel::setSearchQuery,
                            onShowHidden = viewModel::showHidden,
                        )
                    } else {
                        NarrowStoryWorldHubContent(
                            state = current,
                            onBackClick = onBackClick,
                            onEntityClick = onEntityClick,
                            onKindClick = onKindClick,
                            onSearchQueryChange = viewModel::setSearchQuery,
                            onShowHidden = viewModel::showHidden,
                        )
                    }
                }
            }
        }
    }
}

// region header

/** The color-blocked hero: back button, world-title overline, large "Story World" title, search. */
@Composable
private fun StoryWorldHero(
    worldTitle: String,
    searchQuery: String,
    onBackClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (worldTitle.isNotBlank()) {
                    Text(
                        text = worldTitle.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = stringResource(Res.string.story_world_hub_title),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(16.dp))
                ListenUpSearchField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    onSubmit = {},
                    placeholder = stringResource(Res.string.story_world_search_placeholder),
                    onClear = { onSearchQueryChange("") },
                )
            }
        }
    }
}

// endregion

// region narrow layout

@Composable
private fun NarrowStoryWorldHubContent(
    state: StoryWorldHubUiState.Ready,
    onBackClick: () -> Unit,
    onEntityClick: (String) -> Unit,
    onKindClick: (EntityKind) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onShowHidden: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StoryWorldHero(
            worldTitle = state.worldTitle,
            searchQuery = state.searchQuery,
            onBackClick = onBackClick,
            onSearchQueryChange = onSearchQueryChange,
        )
        if (state.searchQuery.isNotBlank()) {
            SearchResultsList(
                results = state.searchResults,
                onEntityClick = onEntityClick,
                modifier = Modifier.padding(16.dp),
            )
        } else if (state.isEmpty) {
            WorldEmptyState(modifier = Modifier.padding(top = 24.dp))
        } else {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                StorySoFarSeamCard()
                if (state.unstartedBooksBanner) {
                    WorldSpoilerBanner()
                }
                HubKindCard(kindGroups = state.kindGroups, onKindClick = { onKindClick(it.kind) })
                if (state.recentEntities.isNotEmpty()) {
                    RecentEntitiesRail(entities = state.recentEntities, onEntityClick = onEntityClick)
                }
                LatestEventsSection(state = state, onShowHidden = onShowHidden)
            }
        }
    }
}

// endregion

// region wide layout

@Composable
private fun WideStoryWorldHubContent(
    state: StoryWorldHubUiState.Ready,
    onBackClick: () -> Unit,
    onEntityClick: (String) -> Unit,
    onKindClick: (EntityKind) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onShowHidden: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        StoryWorldHero(
            worldTitle = state.worldTitle,
            searchQuery = state.searchQuery,
            onBackClick = onBackClick,
            onSearchQueryChange = onSearchQueryChange,
        )
        if (state.searchQuery.isNotBlank()) {
            SearchResultsList(
                results = state.searchResults,
                onEntityClick = onEntityClick,
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
            )
        } else if (state.isEmpty) {
            WorldEmptyState(modifier = Modifier.padding(top = 24.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    modifier = Modifier.width(380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    StorySoFarSeamCard()
                    if (state.unstartedBooksBanner) {
                        WorldSpoilerBanner()
                    }
                    HubKindCard(kindGroups = state.kindGroups, onKindClick = { onKindClick(it.kind) })
                }
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (state.recentEntities.isNotEmpty()) {
                        RecentEntitiesRail(entities = state.recentEntities, onEntityClick = onEntityClick)
                    }
                    LatestEventsSection(state = state, onShowHidden = onShowHidden)
                }
            }
        }
    }
}

// endregion

// region shared sections

/** "Latest events" section header + [HubEventRow] list + the hidden-count reveal row. */
@Composable
private fun LatestEventsSection(
    state: StoryWorldHubUiState.Ready,
    onShowHidden: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(Res.string.story_world_latest_events),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.latestEvents.isNotEmpty()) {
            HubEventRow(events = state.latestEvents)
        }
        if (state.hiddenEventCount > 0 && !state.revealed) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.story_world_hidden_count, state.hiddenEventCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onShowHidden) {
                    Text(stringResource(Res.string.story_world_show_anyway))
                }
            }
        }
    }
}

/** Flat search-results list shown in place of the kind/recent/events sections while searching. */
@Composable
private fun SearchResultsList(
    results: List<EntityCard>,
    onEntityClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(results, key = { it.id }) { entity ->
            SearchResultRow(entity = entity, onClick = { onEntityClick(entity.id) })
        }
    }
}

@Composable
private fun SearchResultRow(
    entity: EntityCard,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EntityTile(name = entity.name, kind = entity.kind, tintSeed = entity.id, size = 50.dp)
        Text(
            text = entity.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// endregion
