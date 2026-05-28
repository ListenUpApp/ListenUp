@file:Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod", "UnusedParameter")

package com.calypsan.listenup.client.features.admin.categories

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import com.calypsan.listenup.client.design.components.ListenUpFab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.domain.model.Genre
import androidx.compose.material3.AlertDialog
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesViewModel
import com.calypsan.listenup.client.presentation.admin.GenreTreeNode
import com.calypsan.listenup.client.presentation.admin.genreMoveCandidates
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_add_genre
import listenup.composeapp.generated.resources.admin_add_subgenre
import listenup.composeapp.generated.resources.admin_confirm_delete_item
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_categories
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.admin_genre_name
import listenup.composeapp.generated.resources.common_no_items
import listenup.composeapp.generated.resources.common_rename
import listenup.composeapp.generated.resources.admin_rename_genre
import listenup.composeapp.generated.resources.admin_tap_to_create_your_first
import listenup.composeapp.generated.resources.common_cancel

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
    var showCreateDialog by remember { mutableStateOf(false) }
    var createParentId by remember { mutableStateOf<String?>(null) }
    var createParentName by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameGenreId by remember { mutableStateOf("") }
    var renameGenreName by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteGenreId by remember { mutableStateOf("") }
    var deleteGenreName by remember { mutableStateOf("") }
    var showMergeDialog by remember { mutableStateOf(false) }
    var mergeSourceId by remember { mutableStateOf("") }
    var mergeSourceName by remember { mutableStateOf("") }
    var showMoveDialog by remember { mutableStateOf(false) }
    var moveSourceId by remember { mutableStateOf("") }
    var moveSourceName by remember { mutableStateOf("") }

    // Show transient mutation-failure error in snackbar (only meaningful in Ready).
    val readyError = (state as? AdminCategoriesUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(Res.string.common_categories)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.common_back))
                        }
                    },
                    actions = {
                        val ready = state as? AdminCategoriesUiState.Ready
                        if (ready != null && ready.tree.isNotEmpty()) {
                            val allExpanded = ready.expandedIds.size >= ready.genres.count {
                                ready.tree.any { root -> hasChildren(root, it.id) }
                            }
                            IconButton(
                                onClick = {
                                    if (allExpanded) viewModel.collapseAll() else viewModel.expandAll()
                                }
                            ) {
                                Icon(
                                    imageVector = if (allExpanded) Icons.Outlined.UnfoldLess else Icons.Outlined.UnfoldMore,
                                    contentDescription = if (allExpanded) "Collapse All" else "Expand All",
                                )
                            }
                        }
                    },
                )
                if ((state as? AdminCategoriesUiState.Ready)?.isSaving == true) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        floatingActionButton = {
            if (state is AdminCategoriesUiState.Ready) {
                ListenUpFab(
                    onClick = {
                        createParentId = null
                        createParentName = null
                        showCreateDialog = true
                    },
                    icon = Icons.Outlined.Add,
                    contentDescription = stringResource(Res.string.admin_add_genre),
                )
            }
        },
    ) { innerPadding ->
        when (val s = state) {
            is AdminCategoriesUiState.Loading -> {
                FullScreenLoadingIndicator()
            }

            is AdminCategoriesUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            is AdminCategoriesUiState.Ready -> {
                AdminCategoriesReadyContent(
                    state = s,
                    onToggleExpanded = viewModel::toggleExpanded,
                    onAddChild = { id, name ->
                        createParentId = id
                        createParentName = name
                        showCreateDialog = true
                    },
                    onRename = { id, name ->
                        renameGenreId = id
                        renameGenreName = name
                        showRenameDialog = true
                    },
                    onDelete = { id, name ->
                        deleteGenreId = id
                        deleteGenreName = name
                        showDeleteDialog = true
                    },
                    onMerge = { id, name ->
                        mergeSourceId = id
                        mergeSourceName = name
                        showMergeDialog = true
                    },
                    onMove = { id, name ->
                        moveSourceId = id
                        moveSourceName = name
                        showMoveDialog = true
                    },
                    onMoveGenre = viewModel::moveGenre,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }

    // Create dialog
    if (showCreateDialog) {
        GenreNameDialog(
            title = if (createParentName != null) "Add Sub-genre" else "Add Root Genre",
            subtitle = createParentName?.let { "Under: $it" },
            initialName = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                viewModel.createGenre(name, createParentId)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }

    // Rename dialog
    if (showRenameDialog) {
        GenreNameDialog(
            title = stringResource(Res.string.admin_rename_genre),
            initialName = renameGenreName,
            confirmLabel = stringResource(Res.string.common_rename),
            onConfirm = { name ->
                viewModel.renameGenre(renameGenreId, name)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false },
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        ListenUpDestructiveDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(Res.string.common_delete_name, "Genre"),
            text = stringResource(Res.string.admin_confirm_delete_item, deleteGenreName),
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = {
                viewModel.deleteGenre(deleteGenreId)
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    // Merge dialog — pick a target genre to merge the source into.
    if (showMergeDialog) {
        val ready = state as? AdminCategoriesUiState.Ready
        MergeGenreDialog(
            sourceName = mergeSourceName,
            candidates = ready?.genres.orEmpty().filter { it.id != mergeSourceId },
            onConfirm = { targetId ->
                viewModel.mergeGenres(mergeSourceId, targetId)
                showMergeDialog = false
            },
            onDismiss = { showMergeDialog = false },
        )
    }

    // Move dialog — pick a new parent (or top level) for the source genre.
    if (showMoveDialog) {
        val ready = state as? AdminCategoriesUiState.Ready
        val source = ready?.genres?.firstOrNull { it.id == moveSourceId }
        MoveGenreDialog(
            sourceName = moveSourceName,
            candidates = if (source != null) genreMoveCandidates(ready.genres, source) else emptyList(),
            onConfirmTarget = { targetId ->
                viewModel.moveGenre(moveSourceId, targetId)
                showMoveDialog = false
            },
            onConfirmTopLevel = {
                viewModel.moveGenre(moveSourceId, null)
                showMoveDialog = false
            },
            onDismiss = { showMoveDialog = false },
        )
    }
}

@Composable
private fun AdminCategoriesReadyContent(
    state: AdminCategoriesUiState.Ready,
    onToggleExpanded: (String) -> Unit,
    onAddChild: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onMove: (String, String) -> Unit,
    onMoveGenre: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Drag state is local to the Ready content — it is only meaningful while
    // the tree is interactive.
    var draggedGenreId by remember { mutableStateOf<String?>(null) }
    var draggedGenreName by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }

    CategoriesContent(
        state = state,
        onToggleExpanded = onToggleExpanded,
        dropTargetId = dropTargetId,
        onAddChild = onAddChild,
        onRename = onRename,
        onDelete = onDelete,
        onMerge = onMerge,
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
        modifier = modifier,
    )
}

/**
 * Reusable dialog for entering/editing a genre name.
 */
@Composable
private fun GenreNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title) },
        text = {
            Column {
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.admin_genre_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

/**
 * Check if a node or any of its descendants has the given ID.
 */
private fun hasChildren(node: GenreTreeNode, id: String): Boolean {
    if (node.genre.id == id) return node.children.isNotEmpty()
    return node.children.any { hasChildren(it, id) }
}

@Composable
private fun CategoriesContent(
    state: AdminCategoriesUiState.Ready,
    onToggleExpanded: (String) -> Unit,
    dropTargetId: String?,
    onAddChild: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onMove: (String, String) -> Unit,
    onDragStart: (String, String) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDropTargetChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.tree.isEmpty()) {
        EmptyCategoriesMessage(modifier = modifier)
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            item {
                Text(
                    text = "${state.genres.size} categories • ${state.totalBookCount} books",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column {
                        state.tree.forEachIndexed { index, rootNode ->
                            CategoryTreeNode(
                                node = rootNode,
                                expandedIds = state.expandedIds,
                                onToggleExpanded = onToggleExpanded,
                                isLast = index == state.tree.lastIndex,
                                dropTargetId = dropTargetId,
                                onAddChild = onAddChild,
                                onRename = onRename,
                                onDelete = onDelete,
                                onMerge = onMerge,
                                onMove = onMove,
                                onDragStart = onDragStart,
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                                onDropTargetChange = onDropTargetChange,
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp)) // FAB clearance
            }
        }
    }
}

@Composable
private fun CategoryTreeNode(
    node: GenreTreeNode,
    expandedIds: Set<String>,
    onToggleExpanded: (String) -> Unit,
    isLast: Boolean,
    dropTargetId: String?,
    onAddChild: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onMove: (String, String) -> Unit,
    onDragStart: (String, String) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDropTargetChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isExpanded = expandedIds.contains(node.genre.id)
    val hasChildren = node.children.isNotEmpty()
    val isDropTarget = dropTargetId == node.genre.id

    Column(modifier = modifier) {
        CategoryRow(
            node = node,
            isExpanded = isExpanded,
            hasChildren = hasChildren,
            isDropTarget = isDropTarget,
            onToggleExpanded = { onToggleExpanded(node.genre.id) },
            onAddChild = { onAddChild(node.genre.id, node.genre.name) },
            onRename = { onRename(node.genre.id, node.genre.name) },
            onDelete = { onDelete(node.genre.id, node.genre.name) },
            onMerge = { onMerge(node.genre.id, node.genre.name) },
            onMove = { onMove(node.genre.id, node.genre.name) },
            onDragStart = { onDragStart(node.genre.id, node.genre.name) },
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
            onDropTargetChange = onDropTargetChange,
        )

        // Show divider if not last item at root level, or if expanded with children
        if (!isLast || (isExpanded && hasChildren)) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = (24 + node.depth * 24).dp),
            )
        }

        // Animated children expansion
        AnimatedVisibility(
            visible = isExpanded && hasChildren,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                node.children.forEachIndexed { index, child ->
                    CategoryTreeNode(
                        node = child,
                        expandedIds = expandedIds,
                        onToggleExpanded = onToggleExpanded,
                        isLast = index == node.children.lastIndex,
                        dropTargetId = dropTargetId,
                        onAddChild = onAddChild,
                        onRename = onRename,
                        onDelete = onDelete,
                        onMerge = onMerge,
                        onMove = onMove,
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                        onDropTargetChange = onDropTargetChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    node: GenreTreeNode,
    isExpanded: Boolean,
    hasChildren: Boolean,
    isDropTarget: Boolean,
    onToggleExpanded: () -> Unit,
    onAddChild: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMerge: () -> Unit,
    onMove: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDropTargetChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "ChevronRotation",
    )

    var showContextMenu by remember { mutableStateOf(false) }
    var rowPosition by remember { mutableStateOf(Offset.Zero) }
    var rowHeight by remember { mutableStateOf(0) }

    val dropHighlightColor = MaterialTheme.colorScheme.primaryContainer

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isDropTarget) {
                    Modifier.background(dropHighlightColor, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .onGloballyPositioned { coordinates ->
                rowPosition = coordinates.positionInRoot()
                rowHeight = coordinates.size.height
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (hasChildren) onToggleExpanded() },
                    onLongClick = { showContextMenu = true },
                )
                .padding(
                    start = (16 + node.depth * 24).dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Expand/collapse icon or spacer
            if (hasChildren) {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation),
                )
            } else {
                Spacer(modifier = Modifier.width(20.dp))
            }

            // Category icon
            Icon(
                imageVector = Icons.Outlined.Category,
                contentDescription = null,
                tint = if (node.depth == 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )

            // Category name
            Text(
                text = node.genre.name,
                style = if (node.depth == 0) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // Book count badge
            if (node.genre.bookCount > 0) {
                Text(
                    text = "${node.genre.bookCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Context menu
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.admin_add_subgenre)) },
                onClick = {
                    showContextMenu = false
                    onAddChild()
                },
                leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_rename)) },
                onClick = {
                    showContextMenu = false
                    onRename()
                },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Merge into…") },
                onClick = {
                    showContextMenu = false
                    onMerge()
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CallMerge, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Move to…") },
                onClick = {
                    showContextMenu = false
                    onMove()
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    showContextMenu = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

@Composable
private fun EmptyCategoriesMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Category,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.common_no_items, "Categories"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.admin_tap_to_create_your_first),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Merge picker — choose which live genre to merge the source into. Source is
 * filtered out of the candidate list. Confirms with the chosen target id.
 */
@Composable
private fun MergeGenreDialog(
    sourceName: String,
    candidates: List<Genre>,
    onConfirm: (targetId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge \"$sourceName\" into…") },
        text = {
            if (candidates.isEmpty()) {
                Text("No other genres available as a merge target.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(candidates, key = { it.id }) { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(candidate.id) }
                                .padding(vertical = 12.dp),
                        ) {
                            Column {
                                Text(text = candidate.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = candidate.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/**
 * Move picker — choose a new parent for the source genre, or promote it to the
 * top level. The manual fallback for drag-based reparenting (Never Stranded).
 * Candidates already exclude the source and its descendants (cycle-safe).
 */
@Composable
private fun MoveGenreDialog(
    sourceName: String,
    candidates: List<Genre>,
    onConfirmTarget: (targetId: String) -> Unit,
    onConfirmTopLevel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move \"$sourceName\" to…") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item(key = "__top_level__") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirmTopLevel() }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(text = "Top level", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (candidates.isEmpty()) {
                    item(key = "__no_candidates__") {
                        Text(
                            text = "No other genres available — only 'Top level' is possible.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(candidates, key = { it.id }) { candidate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirmTarget(candidate.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Column {
                            Text(text = candidate.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = candidate.path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}
