
package com.calypsan.listenup.client.features.admin.categories

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.selected
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import listenup.composeapp.generated.resources.common_more_actions
import listenup.composeapp.generated.resources.common_collapsed
import listenup.composeapp.generated.resources.common_expanded
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import listenup.composeapp.generated.resources.merge_history_open
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.GenreTreeNode
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_add_subgenre
import listenup.composeapp.generated.resources.admin_book_count
import listenup.composeapp.generated.resources.admin_categories_books_count
import listenup.composeapp.generated.resources.admin_merge_into
import listenup.composeapp.generated.resources.admin_move_to
import listenup.composeapp.generated.resources.admin_tap_to_create_your_first
import listenup.composeapp.generated.resources.common_collapse
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_expand
import listenup.composeapp.generated.resources.common_no_items
import listenup.composeapp.generated.resources.common_rename
import org.jetbrains.compose.resources.stringResource

/**
 * Check if a node or any of its descendants has the given ID.
 */
internal fun hasChildren(
    node: GenreTreeNode,
    id: String,
): Boolean {
    if (node.genre.id == id) return node.children.isNotEmpty()
    return node.children.any { hasChildren(it, id) }
}

// The category tree threads expansion + per-row action + drag callbacks down each level;
// a parameter object would only add an indirection layer Compose tooling discourages.
@Suppress("LongParameterList")
@Composable
internal fun CategoriesContent(
    state: AdminCategoriesUiState.Ready,
    onToggleExpanded: (String) -> Unit,
    dropTargetId: String?,
    onAddChild: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onMergeHistory: (String) -> Unit,
    onMove: (String, String) -> Unit,
    onDragStart: (String, String) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDropTargetChange: (String?) -> Unit,
    selectedGenreId: String?,
    onSelect: ((String) -> Unit)?,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    if (state.tree.isEmpty()) {
        EmptyCategoriesMessage(modifier = modifier)
    } else {
        LazyColumn(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding),
        ) {
            item(key = "summary", contentType = "summary") {
                Text(
                    text =
                        stringResource(
                            Res.string.admin_categories_books_count,
                            state.genres.size,
                            state.totalBookCount,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            item(key = "tree-card", contentType = "tree-card") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors =
                        CardDefaults.cardColors(
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
                                canEdit = state.canEditMetadata,
                                canCurate = state.canCurateLibrary,
                                dropTargetId = dropTargetId,
                                onAddChild = onAddChild,
                                onRename = onRename,
                                onDelete = onDelete,
                                onMerge = onMerge,
                                onMergeHistory = onMergeHistory,
                                onMove = onMove,
                                onDragStart = onDragStart,
                                onDragEnd = onDragEnd,
                                onDragCancel = onDragCancel,
                                onDropTargetChange = onDropTargetChange,
                                selectedGenreId = selectedGenreId,
                                onSelect = onSelect,
                            )
                        }
                    }
                }
            }

            item(key = "fab-spacer", contentType = "spacer") {
                Spacer(modifier = Modifier.height(80.dp)) // FAB clearance
            }
        }
    }
}

// Recursive tree node forwards the full expansion + action + drag callback set to each child;
// a parameter object would only add an indirection layer Compose tooling discourages.
@Suppress("LongParameterList")
@Composable
private fun CategoryTreeNode(
    node: GenreTreeNode,
    expandedIds: Set<String>,
    onToggleExpanded: (String) -> Unit,
    isLast: Boolean,
    canEdit: Boolean,
    canCurate: Boolean,
    dropTargetId: String?,
    onAddChild: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onMergeHistory: (String) -> Unit,
    onMove: (String, String) -> Unit,
    onDragStart: (String, String) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDropTargetChange: (String?) -> Unit,
    selectedGenreId: String?,
    onSelect: ((String) -> Unit)?,
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
            canEdit = canEdit,
            canCurate = canCurate,
            onToggleExpanded = { onToggleExpanded(node.genre.id) },
            onAddChild = { onAddChild(node.genre.id, node.genre.name) },
            onRename = { onRename(node.genre.id, node.genre.name) },
            onDelete = { onDelete(node.genre.id, node.genre.name) },
            onMerge = { onMerge(node.genre.id, node.genre.name) },
            onMergeHistory = { onMergeHistory(node.genre.id) },
            onMove = { onMove(node.genre.id, node.genre.name) },
            onDragStart = { onDragStart(node.genre.id, node.genre.name) },
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
            onDropTargetChange = onDropTargetChange,
            isSelected = selectedGenreId == node.genre.id,
            onSelect = onSelect?.let { select -> { select(node.genre.id) } },
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
                        canEdit = canEdit,
                        canCurate = canCurate,
                        dropTargetId = dropTargetId,
                        onAddChild = onAddChild,
                        onRename = onRename,
                        onDelete = onDelete,
                        onMerge = onMerge,
                        onMergeHistory = onMergeHistory,
                        onMove = onMove,
                        onDragStart = onDragStart,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                        onDropTargetChange = onDropTargetChange,
                        selectedGenreId = selectedGenreId,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

// Row holds the full per-row action + drag callback set so the drag gesture can be wired onto
// it without re-threading; a parameter object would only add Compose-discouraged indirection.
// The drag callbacks are the not-yet-wired reparent-gesture seam the tree threads down to each row.
@Suppress("LongParameterList", "UnusedParameter")
@Composable
private fun CategoryRow(
    node: GenreTreeNode,
    isExpanded: Boolean,
    hasChildren: Boolean,
    isDropTarget: Boolean,
    canEdit: Boolean,
    canCurate: Boolean,
    onToggleExpanded: () -> Unit,
    onAddChild: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMerge: () -> Unit,
    onMergeHistory: () -> Unit,
    onMove: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDropTargetChange: (String?) -> Unit,
    isSelected: Boolean,
    onSelect: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "ChevronRotation",
    )

    var showContextMenu by remember { mutableStateOf(false) }
    // With neither permission there is nothing in the menu, so the long-press offers nothing either.
    val openContextMenu: (() -> Unit)? =
        if (canEdit || canCurate) {
            { showContextMenu = true }
        } else {
            null
        }
    var rowPosition by remember { mutableStateOf(Offset.Zero) }
    var rowHeight by remember { mutableIntStateOf(0) }

    val dropHighlightColor = MaterialTheme.colorScheme.primaryContainer
    val selectedColor = MaterialTheme.colorScheme.secondaryContainer

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    when {
                        isDropTarget -> Modifier.background(dropHighlightColor, MaterialTheme.shapes.extraSmall)
                        isSelected -> Modifier.background(selectedColor, MaterialTheme.shapes.extraSmall)
                        else -> Modifier
                    },
                ).onGloballyPositioned { coordinates ->
                    rowPosition = coordinates.positionInRoot()
                    rowHeight = coordinates.size.height
                },
    ) {
        if (onSelect == null) {
            CategoryRowContent(
                node = node,
                isExpanded = isExpanded,
                hasChildren = hasChildren,
                rotation = rotation,
                onToggleExpanded = onToggleExpanded,
                onLongClick = openContextMenu,
            )
        } else {
            SelectableCategoryRowContent(
                node = node,
                isExpanded = isExpanded,
                hasChildren = hasChildren,
                rotation = rotation,
                isSelected = isSelected,
                onSelect = onSelect,
                onToggleExpanded = onToggleExpanded,
                onLongClick = openContextMenu,
            )
        }

        // Context menu
        CategoryContextMenu(
            expanded = showContextMenu,
            onDismiss = { showContextMenu = false },
            canEdit = canEdit,
            canCurate = canCurate,
            onAddChild = onAddChild,
            onRename = onRename,
            onMerge = onMerge,
            onMergeHistory = onMergeHistory,
            onMove = onMove,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun CategoryRowContent(
    node: GenreTreeNode,
    isExpanded: Boolean,
    hasChildren: Boolean,
    rotation: Float,
    onToggleExpanded: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val haptics = LocalHaptics.current
    // The tap expands/collapses and the long-press opens the actions menu — both named for TalkBack,
    // with the expanded state carried as a state rather than baked into the icon's description.
    val expandLabel = stringResource(if (isExpanded) Res.string.common_collapse else Res.string.common_expand)
    val expansionState = stringResource(if (isExpanded) Res.string.common_expanded else Res.string.common_collapsed)
    val moreActionsLabel = stringResource(Res.string.common_more_actions)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    // Our gated haptics.longPress() owns the feel; suppress combinedClickable's
                    // built-in long-press haptic so it doesn't double up (same as BookCard).
                    hapticFeedbackEnabled = false,
                    onClickLabel = if (hasChildren) expandLabel else null,
                    onLongClickLabel = moreActionsLabel.takeIf { onLongClick != null },
                    onClick = {
                        if (hasChildren) {
                            haptics.press()
                            onToggleExpanded()
                        }
                    },
                    onLongClick =
                        onLongClick?.let { open ->
                            {
                                haptics.longPress()
                                open()
                            }
                        },
                ).then(
                    if (hasChildren) Modifier.semantics { stateDescription = expansionState } else Modifier,
                ).padding(
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
                imageVector = ListenUpIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .size(20.dp)
                        .rotate(rotation),
            )
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }

        CategoryRowLabel(node = node)
    }
}

/**
 * The wide layout's row: a tap selects the category for the detail panel, so expanding moves to its
 * own chevron button — named for TalkBack, carrying the expanded state. The long-press menu stays,
 * so the phone's gesture still works on a tablet.
 *
 * Mirrors CategoryRowContent's inputs plus the selection pair; a parameter object would only add an
 * indirection layer Compose tooling discourages.
 */
@Suppress("LongParameterList")
@Composable
private fun SelectableCategoryRowContent(
    node: GenreTreeNode,
    isExpanded: Boolean,
    hasChildren: Boolean,
    rotation: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onToggleExpanded: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val haptics = LocalHaptics.current
    val expandLabel = stringResource(if (isExpanded) Res.string.common_collapse else Res.string.common_expand)
    val expansionState = stringResource(if (isExpanded) Res.string.common_expanded else Res.string.common_collapsed)
    val moreActionsLabel = stringResource(Res.string.common_more_actions)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .combinedClickable(
                    hapticFeedbackEnabled = false,
                    onLongClickLabel = moreActionsLabel.takeIf { onLongClick != null },
                    onClick = {
                        haptics.selectionTick()
                        onSelect()
                    },
                    onLongClick =
                        onLongClick?.let { open ->
                            {
                                haptics.longPress()
                                open()
                            }
                        },
                ).semantics { selected = isSelected }
                // The chevron button's own 14dp inset takes the place of the phone row's padding.
                .padding(start = (2 + node.depth * 24).dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasChildren) {
            IconButton(
                onClick = {
                    haptics.press()
                    onToggleExpanded()
                },
                modifier = Modifier.semantics { stateDescription = expansionState },
            ) {
                Icon(
                    imageVector = ListenUpIcons.ExpandMore,
                    contentDescription = expandLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(rotation),
                )
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
        CategoryRowLabel(node = node)
    }
}

/** A category row's icon, name and book count — the part the phone and wide rows share. */
@Composable
private fun RowScope.CategoryRowLabel(node: GenreTreeNode) {
    // Category icon
    Icon(
        imageVector = ListenUpIcons.Category,
        contentDescription = null,
        tint =
            if (node.depth == 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = Modifier.size(20.dp),
    )

    // Category name
    Text(
        text = node.genre.name,
        style =
            if (node.depth == 0) {
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
            text = stringResource(Res.string.admin_book_count, node.genre.bookCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// One callback per category action plus the two permissions that gate them; a parameter object
// would only add an indirection layer Compose tooling discourages.
@Suppress("LongParameterList")
@Composable
private fun CategoryContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    canEdit: Boolean,
    canCurate: Boolean,
    onAddChild: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onMergeHistory: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = LocalHaptics.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (canEdit) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.admin_add_subgenre)) },
                onClick = {
                    haptics.press()
                    onDismiss()
                    onAddChild()
                },
                leadingIcon = { Icon(ListenUpIcons.Add, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_rename)) },
                onClick = {
                    haptics.press()
                    onDismiss()
                    onRename()
                },
                leadingIcon = { Icon(ListenUpIcons.Edit, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.admin_move_to)) },
                onClick = {
                    haptics.press()
                    onDismiss()
                    onMove()
                },
                leadingIcon = { Icon(ListenUpIcons.ArrowForward, contentDescription = null) },
            )
        }
        if (canCurate) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.admin_merge_into)) },
                onClick = {
                    haptics.press()
                    onDismiss()
                    onMerge()
                },
                leadingIcon = { Icon(ListenUpIcons.CallMerge, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.merge_history_open)) },
                onClick = {
                    haptics.press()
                    onDismiss()
                    onMergeHistory()
                },
                leadingIcon = { Icon(ListenUpIcons.History, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    haptics.press()
                    onDismiss()
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        ListenUpIcons.Delete,
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
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = ListenUpIcons.Category,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
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
