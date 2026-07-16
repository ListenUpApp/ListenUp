package com.calypsan.listenup.client.features.storyworld

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.design.components.EntityTile
import com.calypsan.listenup.client.design.components.ListenUpExtendedFab
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.features.storyworld.components.EntryTimelineRow
import com.calypsan.listenup.client.features.storyworld.components.WorldSpoilerBanner
import com.calypsan.listenup.client.features.storyworld.components.anchorLabelText
import com.calypsan.listenup.client.features.storyworld.components.icon
import com.calypsan.listenup.client.features.storyworld.components.singularLabel
import com.calypsan.listenup.client.presentation.storyworld.EntityCard
import com.calypsan.listenup.client.presentation.storyworld.EntityDetailEvent
import com.calypsan.listenup.client.presentation.storyworld.EntityDetailUiState
import com.calypsan.listenup.client.presentation.storyworld.EntityDetailViewModel
import com.calypsan.listenup.client.presentation.storyworld.EntityEntryRow
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.story_world_delete_entity
import listenup.composeapp.generated.resources.story_world_entity_not_found
import listenup.composeapp.generated.resources.story_world_evolution_seam_body
import listenup.composeapp.generated.resources.story_world_evolution_seam_title
import listenup.composeapp.generated.resources.story_world_fab_add_entry
import listenup.composeapp.generated.resources.story_world_hidden_count
import listenup.composeapp.generated.resources.story_world_rename_entity
import listenup.composeapp.generated.resources.story_world_show_anyway
import listenup.composeapp.generated.resources.story_world_tab_entries
import listenup.composeapp.generated.resources.story_world_tab_evolution

private val HERO_TILE_SIZE = 104.dp
private val WIDE_HERO_WIDTH = 380.dp
private val WIDE_TAB_ROW_WIDTH = 280.dp
private val EVOLUTION_TILE_SIZE = 66.dp
private val EVOLUTION_ICON_SIZE = 30.dp
private val EVOLUTION_BODY_MAX_WIDTH = 280.dp

/** Which tab of the entity detail screen is showing. Evolution is an inert seam until it ships. */
private enum class EntityDetailTab { Entries, Evolution }

/**
 * The Story World entity detail screen — a hero (tile, name, kind), the frontier-gated
 * chronological log for this entity behind an Entries/Evolution segmented control, and
 * rename/delete via the hero's overflow menu. The "Add entry" FAB and each row's Edit action
 * both open [ComposerSheet], prefilled with a mention of this entity for the FAB and loaded for
 * edit for a row.
 */
@Composable
fun EntityDetailScreen(
    entityId: String,
    onBackClick: () -> Unit,
    viewModel: EntityDetailViewModel = koinViewModel(),
) {
    LaunchedEffect(entityId) { viewModel.load(entityId) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                EntityDetailEvent.EntityDeleted -> onBackClick()
            }
        }
    }

    var showComposer by rememberSaveable { mutableStateOf(false) }
    var composerEditEventId by rememberSaveable { mutableStateOf<String?>(null) }

    ListenUpScaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (state is EntityDetailUiState.Ready) {
                ListenUpExtendedFab(
                    onClick = {
                        composerEditEventId = null
                        showComposer = true
                    },
                    icon = Icons.Default.Add,
                    text = stringResource(Res.string.story_world_fab_add_entry),
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                EntityDetailUiState.Idle, EntityDetailUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                EntityDetailUiState.NotFound -> {
                    EntityNotFoundContent(onBackClick = onBackClick)
                }

                is EntityDetailUiState.Ready -> {
                    EntityDetailReadyContent(
                        entityId = entityId,
                        current = current,
                        onBackClick = onBackClick,
                        onRename = viewModel::rename,
                        onDeleteEntity = viewModel::deleteEntity,
                        onDeleteEntry = viewModel::deleteEntry,
                        onShowHidden = viewModel::showHidden,
                        showComposer = showComposer,
                        composerEditEventId = composerEditEventId,
                        onEditEntry = { eventId ->
                            composerEditEventId = eventId
                            showComposer = true
                        },
                        onCloseComposer = { showComposer = false },
                    )
                }
            }
        }
    }
}

/**
 * The Ready-state body: adaptive narrow/wide content plus the rename/delete dialogs and the
 * hosted composer sheet. Extracted from [EntityDetailScreen] to keep the screen's state
 * dispatch simple; dialog and tab state live here because they only exist while Ready.
 */
@Composable
private fun EntityDetailReadyContent(
    entityId: String,
    current: EntityDetailUiState.Ready,
    onBackClick: () -> Unit,
    onRename: (String) -> Unit,
    onDeleteEntity: () -> Unit,
    onDeleteEntry: (String) -> Unit,
    onShowHidden: () -> Unit,
    showComposer: Boolean,
    composerEditEventId: String?,
    onEditEntry: (String?) -> Unit,
    onCloseComposer: () -> Unit,
) {
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(EntityDetailTab.Entries) }

    val wide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    if (wide) {
        WideEntityDetailContent(
            state = current,
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            onBackClick = onBackClick,
            onRenameClick = { showRenameDialog = true },
            onDeleteClick = { showDeleteDialog = true },
            onShowHidden = onShowHidden,
            onEditEntry = onEditEntry,
            onDeleteEntry = onDeleteEntry,
        )
    } else {
        NarrowEntityDetailContent(
            state = current,
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            onBackClick = onBackClick,
            onRenameClick = { showRenameDialog = true },
            onDeleteClick = { showDeleteDialog = true },
            onShowHidden = onShowHidden,
            onEditEntry = onEditEntry,
            onDeleteEntry = onDeleteEntry,
        )
    }

    if (showRenameDialog) {
        RenameEntityDialog(
            currentName = current.entity.name,
            onDismissRequest = { showRenameDialog = false },
            onConfirm = { newName ->
                onRename(newName)
                showRenameDialog = false
            },
        )
    }
    if (showDeleteDialog) {
        DeleteEntityDialog(
            entityName = current.entity.name,
            onDismissRequest = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDeleteEntity()
            },
        )
    }
    if (showComposer) {
        ComposerSheet(
            world = current.world,
            prefillMentionEntityId = if (composerEditEventId == null) entityId else null,
            editEventId = composerEditEventId,
            onDismiss = onCloseComposer,
        )
    }
}

// region not found

@Composable
private fun EntityNotFoundContent(onBackClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(8.dp)) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.story_world_entity_not_found),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}

// endregion

// region narrow layout

@Composable
private fun NarrowEntityDetailContent(
    state: EntityDetailUiState.Ready,
    selectedTab: EntityDetailTab,
    onTabSelected: (EntityDetailTab) -> Unit,
    onBackClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShowHidden: () -> Unit,
    onEditEntry: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp),
        ) {
            Column {
                EntityHeroNavRow(
                    onBackClick = onBackClick,
                    onRenameClick = onRenameClick,
                    onDeleteClick = onDeleteClick,
                    applyStatusBarInset = true,
                )
                EntityHeroCenteredInfo(entity = state.entity)
            }
        }
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (state.unstartedBooksBanner) {
                WorldSpoilerBanner()
            }
            EntityDetailTabRow(selectedTab = selectedTab, onTabSelected = onTabSelected)
            EntityDetailTabContent(
                selectedTab = selectedTab,
                state = state,
                onShowHidden = onShowHidden,
                onEditEntry = onEditEntry,
                onDeleteEntry = onDeleteEntry,
            )
        }
    }
}

// endregion

// region wide layout

@Composable
private fun WideEntityDetailContent(
    state: EntityDetailUiState.Ready,
    selectedTab: EntityDetailTab,
    onTabSelected: (EntityDetailTab) -> Unit,
    onBackClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShowHidden: () -> Unit,
    onEditEntry: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Surface(
            modifier = Modifier.width(WIDE_HERO_WIDTH).fillMaxHeight(),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(32.dp),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                EntityHeroNavRow(
                    onBackClick = onBackClick,
                    onRenameClick = onRenameClick,
                    onDeleteClick = onDeleteClick,
                    applyStatusBarInset = false,
                )
                EntityHeroCenteredInfo(entity = state.entity)
                if (state.unstartedBooksBanner) {
                    WorldSpoilerBanner(
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(32.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                EntityDetailTabRow(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    modifier = Modifier.width(WIDE_TAB_ROW_WIDTH),
                )
                EntityDetailTabContent(
                    selectedTab = selectedTab,
                    state = state,
                    onShowHidden = onShowHidden,
                    onEditEntry = onEditEntry,
                    onDeleteEntry = onDeleteEntry,
                )
            }
        }
    }
}

// endregion

// region shared hero pieces

@Composable
private fun EntityHeroNavRow(
    onBackClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    applyStatusBarInset: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .let { if (applyStatusBarInset) it.windowInsetsPadding(WindowInsets.statusBars) else it }
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(Res.string.common_back),
            )
        }
        EntityOverflowMenu(onRenameClick = onRenameClick, onDeleteClick = onDeleteClick)
    }
}

@Composable
private fun EntityOverflowMenu(
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(imageVector = Icons.Default.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.story_world_rename_entity)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onRenameClick()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Res.string.story_world_delete_entity),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onDeleteClick()
                },
            )
        }
    }
}

@Composable
private fun EntityHeroCenteredInfo(entity: EntityCard) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EntityTile(name = entity.name, kind = entity.kind, tintSeed = entity.id, size = HERO_TILE_SIZE)
        Spacer(Modifier.height(16.dp))
        Text(
            text = entity.name,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(10.dp))
        EntityKindPill(kind = entity.kind)
    }
}

@Composable
private fun EntityKindPill(kind: EntityKind) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(imageVector = kind.icon(), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = kind.singularLabel(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// endregion

// region tabs

@Composable
private fun EntityDetailTabRow(
    selectedTab: EntityDetailTab,
    onTabSelected: (EntityDetailTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        EntityDetailTab.entries.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = EntityDetailTab.entries.size),
            ) {
                Text(
                    text =
                        when (tab) {
                            EntityDetailTab.Entries -> stringResource(Res.string.story_world_tab_entries)
                            EntityDetailTab.Evolution -> stringResource(Res.string.story_world_tab_evolution)
                        },
                )
            }
        }
    }
}

@Composable
private fun EntityDetailTabContent(
    selectedTab: EntityDetailTab,
    state: EntityDetailUiState.Ready,
    onShowHidden: () -> Unit,
    onEditEntry: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    when (selectedTab) {
        EntityDetailTab.Entries -> {
            EntriesTabContent(
                entries = state.entries,
                hiddenCount = state.hiddenCount,
                revealed = state.revealed,
                onShowHidden = onShowHidden,
                onEditEntry = onEditEntry,
                onDeleteEntry = onDeleteEntry,
            )
        }

        EntityDetailTab.Evolution -> {
            EvolutionSeamContent()
        }
    }
}

@Composable
private fun EntriesTabContent(
    entries: List<EntityEntryRow>,
    hiddenCount: Int,
    revealed: Boolean,
    onShowHidden: () -> Unit,
    onEditEntry: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    Column {
        entries.forEachIndexed { index, entry ->
            EntryTimelineRow(
                index = index + 1,
                renderedText = entry.renderedText,
                anchorLabel = anchorLabelText(entry.anchor),
                isLast = index == entries.lastIndex,
                onEdit = { onEditEntry(entry.id) },
                onDelete = { onDeleteEntry(entry.id) },
            )
        }
        if (hiddenCount > 0 && !revealed) {
            HiddenCountRow(hiddenCount = hiddenCount, onShowHidden = onShowHidden)
        }
        if (entries.isEmpty() && hiddenCount == 0) {
            // The "Add entry" FAB is the empty entries tab's only affordance — this quiet
            // placeholder is deliberate rather than a bespoke empty state.
            Spacer(modifier = Modifier.height(1.dp))
        }
    }
}

@Composable
private fun HiddenCountRow(
    hiddenCount: Int,
    onShowHidden: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.story_world_hidden_count, hiddenCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onShowHidden) {
            Text(stringResource(Res.string.story_world_show_anyway))
        }
    }
}

@Composable
private fun EvolutionSeamContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(EVOLUTION_TILE_SIZE)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(EVOLUTION_ICON_SIZE),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(Res.string.story_world_evolution_seam_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.story_world_evolution_seam_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EVOLUTION_BODY_MAX_WIDTH),
        )
    }
}

// endregion
