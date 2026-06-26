package com.calypsan.listenup.client.features.admin.collections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpFab
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.TonalIconTile
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_collection_count
import listenup.composeapp.generated.resources.admin_collection_name
import listenup.composeapp.generated.resources.admin_collection_new_collection
import listenup.composeapp.generated.resources.admin_collection_shared_book_sets
import listenup.composeapp.generated.resources.admin_collections_count
import listenup.composeapp.generated.resources.admin_create_a_collection_to_organize
import listenup.composeapp.generated.resources.admin_create_collection
import listenup.composeapp.generated.resources.admin_enter_a_name_for_the
import listenup.composeapp.generated.resources.common_book_count
import listenup.composeapp.generated.resources.common_books_count
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_collections
import listenup.composeapp.generated.resources.common_create
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.common_administration
import listenup.composeapp.generated.resources.common_no_items_yet
import org.jetbrains.compose.resources.stringResource

private const val CARD_CORNER_DP = 24
private const val CARD_CORNER_WIDE_DP = 28
private const val CARD_PADDING_DP = 18
private const val CARD_PADDING_WIDE_DP = 22
private const val GRID_GAP_DP = 16
private const val GRID_GAP_WIDE_DP = 20
private const val FAB_SPACER_DP = 88
private const val BADGE_SIZE_DP = 56
private const val DELETE_TILE_SIZE_DP = 40
private const val EMPTY_BADGE_SIZE_DP = 104
private const val EMPTY_BADGE_ICON_DP = 48
private const val BOOK_DOT_SIZE_DP = 3
private const val ACCESS_ICON_SIZE_DP = 14

/**
 * Admin screen for managing collections, redesigned to the M3 Expressive mockup.
 *
 * Lists every collection as an expressive card carrying a scallop-badge folder icon,
 * book count, and access indicator, with create and delete affordances. Tapping a card
 * navigates to its detail screen; the trailing delete icon opens a destructive confirmation.
 * Collections are admin-managed server-curated shared book sets.
 */
@Composable
fun AdminCollectionsScreen(
    viewModel: AdminCollectionsViewModel,
    onBackClick: () -> Unit,
    onCollectionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var collectionToDelete by remember { mutableStateOf<Collection?>(null) }

    // Transient mutation-failure error in snackbar (only meaningful in Ready).
    val readyError = (state as? AdminCollectionsUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Create-success snackbar (only meaningful in Ready).
    val readyCreateSuccess = (state as? AdminCollectionsUiState.Ready)?.createSuccess == true
    LaunchedEffect(readyCreateSuccess) {
        if (readyCreateSuccess) {
            showCreateDialog = false // Dismiss dialog FIRST (before suspend)
            viewModel.clearCreateSuccess()
            snackbarHostState.showSnackbar("Collection created") // This suspends
        }
    }

    val isWide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(TwoPaneMinWidth.value.toInt())

    ListenUpScaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state is AdminCollectionsUiState.Ready) {
                ListenUpFab(
                    onClick = { showCreateDialog = true },
                    icon = Icons.Outlined.Add,
                    contentDescription = stringResource(Res.string.admin_create_collection),
                )
            }
        },
    ) { innerPadding ->
        CollectionsBody(
            state = state,
            isWide = isWide,
            innerPadding = innerPadding,
            onBackClick = onBackClick,
            onCollectionClick = onCollectionClick,
            onDeleteClick = { collectionToDelete = it },
            onNewCollectionClick = { showCreateDialog = true },
        )
    }

    // Create collection dialog — only meaningful in Ready (FAB is hidden otherwise).
    val ready = state as? AdminCollectionsUiState.Ready
    if (showCreateDialog && ready != null) {
        CreateCollectionDialog(
            isCreating = ready.isCreating,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name -> viewModel.createCollection(name) },
        )
    }

    // Delete confirmation dialog
    collectionToDelete?.let { collection ->
        val warningText =
            if (collection.bookCount > 0) {
                """
                Are you sure you want to delete "${collection.name}"?

                The ${collection.bookCount} book${if (collection.bookCount != 1) "s" else ""} in this collection will become visible to all users.
                """.trimIndent()
            } else {
                """Are you sure you want to delete "${collection.name}"?"""
            }

        ListenUpDestructiveDialog(
            onDismissRequest = { collectionToDelete = null },
            title = stringResource(Res.string.common_delete_name, "Collection"),
            text = warningText,
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = {
                viewModel.deleteCollection(collection.id)
                collectionToDelete = null
            },
            onDismiss = { collectionToDelete = null },
        )
    }
}

@Composable
private fun CollectionsBody(
    state: AdminCollectionsUiState,
    isWide: Boolean,
    innerPadding: PaddingValues,
    onBackClick: () -> Unit,
    onCollectionClick: (String) -> Unit,
    onDeleteClick: (Collection) -> Unit,
    onNewCollectionClick: () -> Unit,
) {
    when (state) {
        is AdminCollectionsUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is AdminCollectionsUiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is AdminCollectionsUiState.Ready -> {
            CollectionsReadyContent(
                state = state,
                isWide = isWide,
                innerPadding = innerPadding,
                onBackClick = onBackClick,
                onCollectionClick = onCollectionClick,
                onDeleteClick = onDeleteClick,
                onNewCollectionClick = onNewCollectionClick,
            )
        }
    }
}

@Composable
private fun collectionsSubtitle(state: AdminCollectionsUiState.Ready): String =
    if (state.collections.isEmpty()) {
        stringResource(Res.string.admin_collection_shared_book_sets)
    } else {
        val countLabel =
            if (state.collections.size == 1) {
                stringResource(Res.string.admin_collection_count, 1)
            } else {
                stringResource(Res.string.admin_collections_count, state.collections.size)
            }
        val totalBooks = state.collections.sumOf { it.bookCount }
        val booksLabel =
            if (totalBooks == 1) {
                stringResource(Res.string.common_book_count, totalBooks)
            } else {
                stringResource(Res.string.common_books_count, totalBooks)
            }
        "$countLabel · $booksLabel"
    }

@Composable
private fun CollectionsReadyContent(
    state: AdminCollectionsUiState.Ready,
    isWide: Boolean,
    innerPadding: PaddingValues,
    onBackClick: () -> Unit,
    onCollectionClick: (String) -> Unit,
    onDeleteClick: (Collection) -> Unit,
    onNewCollectionClick: () -> Unit,
) {
    val subtitle = collectionsSubtitle(state)

    Column(modifier = Modifier.fillMaxSize()) {
        ColorBlockHero(
            title = stringResource(Res.string.common_collections),
            badgeIcon = Icons.Outlined.FolderSpecial,
            onBack = onBackClick,
            overline = if (isWide) stringResource(Res.string.common_administration) else null,
            supportingText = subtitle,
        )

        if (state.collections.isEmpty()) {
            CollectionsEmptyState(
                modifier = Modifier.weight(1f).padding(innerPadding),
            )
        } else {
            val gap = if (isWide) GRID_GAP_WIDE_DP.dp else GRID_GAP_DP.dp
            val gridPad = if (isWide) 20.dp else 16.dp

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding =
                    PaddingValues(
                        start = gridPad,
                        end = gridPad,
                        top = gridPad,
                        bottom = innerPadding.calculateBottomPadding() + FAB_SPACER_DP.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = state.collections, key = { it.id }) { collection ->
                    CollectionCard(
                        collection = collection,
                        isDeleting = state.deletingCollectionId == collection.id,
                        isWide = isWide,
                        onClick = { onCollectionClick(collection.id) },
                        onDeleteClick = { onDeleteClick(collection) },
                    )
                }
                item(span = { GridItemSpan(1) }) {
                    NewCollectionCard(
                        isWide = isWide,
                        onClick = onNewCollectionClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(
    collection: Collection,
    isDeleting: Boolean,
    isWide: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cornerDp = if (isWide) CARD_CORNER_WIDE_DP.dp else CARD_CORNER_DP.dp
    val padDp = if (isWide) CARD_PADDING_WIDE_DP.dp else CARD_PADDING_DP.dp
    val badgeSize = if (isWide) 64.dp else BADGE_SIZE_DP.dp

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerDp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(padDp)) {
            CollectionCardTopRow(
                badgeSize = badgeSize,
                isSystem = collection.isSystem,
                isDeleting = isDeleting,
                onDeleteClick = onDeleteClick,
            )
            Spacer(modifier = Modifier.height(if (isWide) 18.dp else 14.dp))
            CollectionCardMeta(collection = collection)
        }
    }
}

@Composable
private fun CollectionCardTopRow(
    badgeSize: Dp,
    isSystem: Boolean,
    isDeleting: Boolean,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        ScallopBadge(
            size = badgeSize,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.FolderSpecial,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(badgeSize * 0.5f),
            )
        }

        when {
            isSystem -> {
                TonalIconTile(
                    icon = Icons.Outlined.Lock,
                    size = DELETE_TILE_SIZE_DP.dp,
                    danger = false,
                )
            }

            isDeleting -> {
                ListenUpLoadingIndicatorSmall()
            }

            else -> {
                TonalIconTile(
                    icon = Icons.Outlined.Delete,
                    size = DELETE_TILE_SIZE_DP.dp,
                    danger = true,
                    modifier = Modifier.clickable(onClick = onDeleteClick),
                )
            }
        }
    }
}

@Composable
private fun CollectionCardMeta(
    collection: Collection,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = collection.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        CollectionCardSubtitle(collection = collection)
    }
}

@Composable
private fun CollectionCardSubtitle(
    collection: Collection,
    modifier: Modifier = Modifier,
) {
    val bookLabel =
        if (collection.bookCount == 1) {
            stringResource(Res.string.common_book_count, collection.bookCount)
        } else {
            stringResource(Res.string.common_books_count, collection.bookCount)
        }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = bookLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier =
                Modifier
                    .size(BOOK_DOT_SIZE_DP.dp)
                    .background(MaterialTheme.colorScheme.outline, shape = RoundedCornerShape(50)),
        )
        androidx.compose.material3.Icon(
            imageVector = Icons.Outlined.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(ACCESS_ICON_SIZE_DP.dp),
        )
    }
}

@Composable
private fun NewCollectionCard(
    isWide: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cornerDp = if (isWide) CARD_CORNER_WIDE_DP.dp else CARD_CORNER_DP.dp
    val minHeight = if (isWide) 196.dp else 168.dp

    Surface(
        modifier = modifier.fillMaxWidth().height(minHeight).clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerDp),
        color = Color.Transparent,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(if (isWide) CARD_PADDING_WIDE_DP.dp else CARD_PADDING_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ScallopBadge(
                size = if (isWide) 64.dp else BADGE_SIZE_DP.dp,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(if (isWide) 32.dp else 28.dp),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.admin_collection_new_collection),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun CollectionsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ScallopBadge(
            size = EMPTY_BADGE_SIZE_DP.dp,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(EMPTY_BADGE_ICON_DP.dp),
            )
        }
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = stringResource(Res.string.common_no_items_yet, stringResource(Res.string.common_collections)),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(Res.string.admin_create_a_collection_to_organize),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreateCollectionDialog(
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(Res.string.admin_create_collection)) },
        text = {
            ListenUpTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(Res.string.admin_collection_name),
                enabled = !isCreating,
                supportingText = stringResource(Res.string.admin_enter_a_name_for_the),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = !isCreating && name.isNotBlank(),
            ) {
                if (isCreating) {
                    ListenUpLoadingIndicatorSmall()
                } else {
                    Text(stringResource(Res.string.common_create))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCreating,
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}
