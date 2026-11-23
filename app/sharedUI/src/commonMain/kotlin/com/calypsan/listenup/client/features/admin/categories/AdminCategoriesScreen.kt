
package com.calypsan.listenup.client.features.admin.categories

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.design.theme.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpFab
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.LoadFailed
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.error.localizedString
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_add_genre
import listenup.composeapp.generated.resources.admin_collapse_all
import listenup.composeapp.generated.resources.admin_expand_all
import listenup.composeapp.generated.resources.common_categories
import org.jetbrains.compose.resources.stringResource

/**
 * Admin screen for managing the category (genre) tree.
 *
 * Displays a hierarchical tree view of all system genres with:
 * - Expandable/collapsible nodes
 * - Book count per category
 * - Long-press context menu for rename/delete
 * - FAB to create new genres
 * - Drag and drop for reparenting
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminCategoriesScreen(
    viewModel: AdminCategoriesViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialog state — hoisted to top level so both the FAB (in the Scaffold)
    // and the per-row context menus (in the Ready content) can trigger dialogs.
    val showCreateDialogState = remember { mutableStateOf(false) }
    val createParentIdState = remember { mutableStateOf<String?>(null) }
    val createParentNameState = remember { mutableStateOf<String?>(null) }
    val showRenameDialogState = remember { mutableStateOf(false) }
    val renameGenreIdState = remember { mutableStateOf("") }
    val renameGenreNameState = remember { mutableStateOf("") }
    val showDeleteDialogState = remember { mutableStateOf(false) }
    val deleteGenreIdState = remember { mutableStateOf("") }
    val deleteGenreNameState = remember { mutableStateOf("") }
    val showMergeDialogState = remember { mutableStateOf(false) }
    val mergeSourceIdState = remember { mutableStateOf("") }
    val mergeSourceNameState = remember { mutableStateOf("") }
    val showMoveDialogState = remember { mutableStateOf(false) }
    val moveSourceIdState = remember { mutableStateOf("") }
    val moveSourceNameState = remember { mutableStateOf("") }

    // Show transient mutation-failure error in snackbar (only meaningful in Ready).
    val readyError = (state as? AdminCategoriesUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let { error ->
            snackbarHostState.showSnackbar(error.localizedString())
            viewModel.clearError()
        }
    }

    ListenUpScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CategoriesTopBar(
                state = state,
                onBackClick = onBackClick,
                onToggleAll = { allExpanded ->
                    if (allExpanded) viewModel.collapseAll() else viewModel.expandAll()
                },
            )
        },
        floatingActionButton = {
            if ((state as? AdminCategoriesUiState.Ready)?.canEditMetadata == true) {
                ListenUpFab(
                    onClick = {
                        createParentIdState.value = null
                        createParentNameState.value = null
                        showCreateDialogState.value = true
                    },
                    icon = ListenUpIcons.Add,
                    contentDescription = stringResource(Res.string.admin_add_genre),
                )
            }
        },
    ) { innerPadding ->
        CategoriesScreenBody(
            state = state,
            innerPadding = innerPadding,
            onRetry = viewModel::retry,
            onToggleExpanded = viewModel::toggleExpanded,
            onAddChild = { id, name ->
                createParentIdState.value = id
                createParentNameState.value = name
                showCreateDialogState.value = true
            },
            onRename = { id, name ->
                renameGenreIdState.value = id
                renameGenreNameState.value = name
                showRenameDialogState.value = true
            },
            onDelete = { id, name ->
                deleteGenreIdState.value = id
                deleteGenreNameState.value = name
                showDeleteDialogState.value = true
            },
            onMerge = { id, name ->
                mergeSourceIdState.value = id
                mergeSourceNameState.value = name
                showMergeDialogState.value = true
            },
            onMergeHistory = viewModel::openMergeHistory,
            onMove = { id, name ->
                moveSourceIdState.value = id
                moveSourceNameState.value = name
                showMoveDialogState.value = true
            },
            onMoveGenre = viewModel::moveGenre,
        )
    }

    CreateGenreDialog(
        viewModel = viewModel,
        showDialogState = showCreateDialogState,
        parentIdState = createParentIdState,
        parentNameState = createParentNameState,
    )

    RenameGenreDialog(
        viewModel = viewModel,
        showDialogState = showRenameDialogState,
        genreIdState = renameGenreIdState,
        genreNameState = renameGenreNameState,
    )

    DeleteGenreDialog(
        viewModel = viewModel,
        showDialogState = showDeleteDialogState,
        genreIdState = deleteGenreIdState,
        genreNameState = deleteGenreNameState,
    )

    MergeGenreDialogHost(
        viewModel = viewModel,
        state = state,
        showDialogState = showMergeDialogState,
        sourceIdState = mergeSourceIdState,
        sourceNameState = mergeSourceNameState,
    )

    MoveGenreDialogHost(
        viewModel = viewModel,
        state = state,
        showDialogState = showMoveDialogState,
        sourceIdState = moveSourceIdState,
        sourceNameState = moveSourceNameState,
    )
    GenreMergeHistorySheet(viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoriesTopBar(
    state: AdminCategoriesUiState,
    onBackClick: () -> Unit,
    onToggleAll: (allExpanded: Boolean) -> Unit,
) {
    val haptics = LocalHaptics.current
    Column {
        ListenUpTopAppBar(
            title = stringResource(Res.string.common_categories),
            onBack = onBackClick,
            actions = {
                val ready = state as? AdminCategoriesUiState.Ready
                if (ready != null && ready.tree.isNotEmpty()) {
                    val allExpanded =
                        ready.expandedIds.size >=
                            ready.genres.count {
                                ready.tree.any { root -> hasChildren(root, it.id) }
                            }
                    IconButton(
                        onClick = {
                            haptics.press()
                            onToggleAll(allExpanded)
                        },
                    ) {
                        Icon(
                            imageVector = if (allExpanded) ListenUpIcons.UnfoldLess else ListenUpIcons.UnfoldMore,
                            contentDescription =
                                stringResource(
                                    if (allExpanded) Res.string.admin_collapse_all else Res.string.admin_expand_all,
                                ),
                        )
                    }
                }
            },
        )
        if ((state as? AdminCategoriesUiState.Ready)?.isSaving == true) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

// CategoriesScreenBody routes per-row callbacks straight through to the tree; flattening
// them into a parameter object would only add an indirection layer Compose tooling discourages.
@Suppress("LongParameterList")
@Composable
private fun CategoriesScreenBody(
    state: AdminCategoriesUiState,
    innerPadding: PaddingValues,
    onRetry: () -> Unit,
    onToggleExpanded: (String) -> Unit,
    onAddChild: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onMergeHistory: (String) -> Unit,
    onMove: (String, String) -> Unit,
    onMoveGenre: (String, String?) -> Unit,
) {
    when (val s = state) {
        is AdminCategoriesUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is AdminCategoriesUiState.Error -> {
            LoadFailed(message = s.error.localized(), onRetry = onRetry, modifier = Modifier.padding(innerPadding))
        }

        is AdminCategoriesUiState.Ready -> {
            AdminCategoriesReadyContent(
                state = s,
                onToggleExpanded = onToggleExpanded,
                onAddChild = onAddChild,
                onRename = onRename,
                onDelete = onDelete,
                onMerge = onMerge,
                onMergeHistory = onMergeHistory,
                onMove = onMove,
                onMoveGenre = onMoveGenre,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * The loaded category tree with its drag-to-reparent state. A phone shows the tree alone; from the
 * medium width up the tree sits beside a [CategoryDetailPanel] for the category the admin selects.
 *
 * The Ready body forwards the tree's per-row actions and the reparent intent; a parameter object
 * would only add an indirection layer Compose tooling discourages.
 */
@Suppress("LongParameterList")
@Composable
internal fun AdminCategoriesReadyContent(
    state: AdminCategoriesUiState.Ready,
    onToggleExpanded: (String) -> Unit,
    onAddChild: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onMergeHistory: (String) -> Unit,
    onMove: (String, String) -> Unit,
    onMoveGenre: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWide =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    // The wide layout's selection: which category the detail panel shows. Kept across rotation, and
    // dropped naturally when the category disappears (the panel then asks for a new selection).
    var selectedGenreId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectCategory: (String) -> Unit = { id -> selectedGenreId = id }

    // Drag state is local to the Ready content — it is only meaningful while
    // the tree is interactive.
    var draggedGenreId by remember { mutableStateOf<String?>(null) }
    var draggedGenreName by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }

    val tree: @Composable (Modifier) -> Unit = { treeModifier ->
        CategoriesContent(
            state = state,
            onToggleExpanded = onToggleExpanded,
            dropTargetId = dropTargetId,
            onAddChild = onAddChild,
            onRename = onRename,
            onDelete = onDelete,
            onMerge = onMerge,
            onMergeHistory = onMergeHistory,
            onMove = onMove,
            onDragStart = { id, name ->
                draggedGenreId = id
                draggedGenreName = name
            },
            onDragEnd = {
                val dragged = draggedGenreId
                val target = dropTargetId
                if (dragged != null && target != null && dragged != target) {
                    onMoveGenre(dragged, target)
                }
                draggedGenreId = null
                draggedGenreName = null
                dragOffset = Offset.Zero
                dropTargetId = null
            },
            onDragCancel = {
                draggedGenreId = null
                draggedGenreName = null
                dragOffset = Offset.Zero
                dropTargetId = null
            },
            onDropTargetChange = { dropTargetId = it },
            selectedGenreId = if (isWide) selectedGenreId else null,
            onSelect = selectCategory.takeIf { isWide },
            horizontalPadding = if (isWide) 0.dp else 16.dp,
            modifier = treeModifier,
        )
    }

    if (!isWide || state.tree.isEmpty()) {
        tree(modifier)
        return
    }

    // The wide form: the tree keeps one column — a hierarchy read top to bottom, whose rows reparent
    // by dragging onto one another, does not survive being dealt into columns — and the selected
    // category gets a detail panel beside it, its actions as visible rows rather than a long-press.
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        tree(Modifier.weight(1f))
        CategoryDetailPanel(
            genre = state.genres.firstOrNull { it.id == selectedGenreId },
            canEdit = state.canEditMetadata,
            canCurate = state.canCurateLibrary,
            onAddChild = onAddChild,
            onRename = onRename,
            onDelete = onDelete,
            onMerge = onMerge,
            onMergeHistory = onMergeHistory,
            onMove = onMove,
            modifier = Modifier.width(CategoryDetailPanelWidth).fillMaxHeight(),
        )
    }
}
