package com.calypsan.listenup.client.features.storyworld

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.design.components.EntityTile
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.features.storyworld.components.entryCountLabel
import com.calypsan.listenup.client.features.storyworld.components.icon
import com.calypsan.listenup.client.features.storyworld.components.pluralLabel
import com.calypsan.listenup.client.presentation.storyworld.EntityListGroup
import com.calypsan.listenup.client.presentation.storyworld.EntityListRow
import com.calypsan.listenup.client.presentation.storyworld.StoryWorldEntityListUiState
import com.calypsan.listenup.client.presentation.storyworld.StoryWorldEntityListViewModel
import com.calypsan.listenup.client.presentation.storyworld.WorldRef
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.story_world_empty_title
import listenup.composeapp.generated.resources.story_world_hub_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val GROUP_ICON_TILE_SIZE = 34.dp

/**
 * Every entity in a Story World, grouped by kind — reached either from the hub's "See all" (no
 * [kindFilter]) or from tapping a kind card ([kindFilter] set to just that kind).
 */
@Composable
fun StoryWorldEntityListScreen(
    seriesId: String?,
    bookId: String?,
    kindFilter: EntityKind?,
    onBackClick: () -> Unit,
    onEntityClick: (String) -> Unit,
    viewModel: StoryWorldEntityListViewModel = koinViewModel(),
) {
    LaunchedEffect(seriesId, bookId, kindFilter) {
        viewModel.load(WorldRef(seriesId, bookId), kindFilter)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    ListenUpScaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                StoryWorldEntityListUiState.Idle, StoryWorldEntityListUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is StoryWorldEntityListUiState.Ready -> {
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        EntityListHero(kindFilter = kindFilter, onBackClick = onBackClick)
                        if (current.groups.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.story_world_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        } else {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(26.dp),
                            ) {
                                current.groups.forEach { group ->
                                    EntityListGroupSection(group = group, onEntityClick = onEntityClick)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The color-blocked hero: back button + a title reflecting [kindFilter] (or the generic hub title when null). */
@Composable
private fun EntityListHero(
    kindFilter: EntityKind?,
    onBackClick: () -> Unit,
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
            Text(
                text = kindFilter?.pluralLabel() ?: stringResource(Res.string.story_world_hub_title),
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun EntityListGroupSection(
    group: EntityListGroup,
    onEntityClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TonalIconTile(icon = group.kind.icon(), size = GROUP_ICON_TILE_SIZE)
            Text(
                text = group.kind.pluralLabel(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            CountPill(count = group.rows.size)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column {
                group.rows.forEachIndexed { index, row ->
                    EntityListItemRow(row = row, onClick = { onEntityClick(row.id) })
                    if (index != group.rows.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 14.dp + 50.dp + 14.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountPill(count: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EntityListItemRow(
    row: EntityListRow,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EntityTile(name = row.name, kind = row.kind, tintSeed = row.id, size = 50.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entryCountLabel(row.entryCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
