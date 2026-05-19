package com.calypsan.listenup.client.features.admin.collections

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import com.calypsan.listenup.client.design.components.ListenUpFab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.model.Collection
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.admin_collection_name
import listenup.composeapp.generated.resources.common_collections
import listenup.composeapp.generated.resources.common_create
import listenup.composeapp.generated.resources.admin_create_a_collection_to_organize
import listenup.composeapp.generated.resources.admin_create_collection
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.admin_enter_a_name_for_the
import listenup.composeapp.generated.resources.common_no_items
import listenup.composeapp.generated.resources.common_cancel

/**
 * Admin screen for managing collections.
 *
 * Displays a list of collections with create and delete functionality.
 * Tap a collection to navigate to its detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.common_collections)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.common_back))
                    }
                },
            )
        },
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
        AdminCollectionsBody(
            state = state,
            innerPadding = innerPadding,
            onCollectionClick = onCollectionClick,
            onDeleteClick = { collectionToDelete = it },
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
                    |Are you sure you want to delete "${collection.name}"?
                    |
                    |The ${collection.bookCount} book${if (collection.bookCount != 1) "s" else ""} in this collection will become visible to all users.
                """.trimMargin()
            } else {
                "Are you sure you want to delete \"${collection.name}\"?"
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
private fun AdminCollectionsBody(
    state: AdminCollectionsUiState,
    innerPadding: PaddingValues,
    onCollectionClick: (String) -> Unit,
    onDeleteClick: (Collection) -> Unit,
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
            AdminCollectionsReadyContent(
                state = state,
                onCollectionClick = onCollectionClick,
                onDeleteClick = onDeleteClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun AdminCollectionsReadyContent(
    state: AdminCollectionsUiState.Ready,
    onCollectionClick: (String) -> Unit,
    onDeleteClick: (Collection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.collections.isEmpty()) {
        EmptyCollectionsMessage(modifier = modifier)
    } else {
        LazyColumn(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
        ) {
            item {
                Text(
                    text = "${state.collections.size} collection${if (state.collections.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors =
                        CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                ) {
                    Column {
                        state.collections.forEachIndexed { index, collection ->
                            CollectionRow(
                                collection = collection,
                                isDeleting = state.deletingCollectionId == collection.id,
                                onClick = { onCollectionClick(collection.id) },
                                onDeleteClick = { onDeleteClick(collection) },
                            )
                            if (index < state.collections.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(FAB_SPACER_HEIGHT_DP.dp)) // Space for FAB
            }
        }
    }
}

private const val FAB_SPACER_HEIGHT_DP = 88

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionRow(
    collection: Collection,
    isDeleting: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
        )

    // Trigger delete when swiped, then snap back
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDeleteClick()
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    Color.Transparent
                },
                label = "SwipeBackground",
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(color)
                        .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(Res.string.common_delete),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = collection.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${collection.bookCount} book${if (collection.bookCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isDeleting) {
                ListenUpLoadingIndicatorSmall()
            }
        }
    }
}

@Composable
private fun EmptyCollectionsMessage(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.common_no_items, "Collections"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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

    androidx.compose.material3.AlertDialog(
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
            androidx.compose.material3.TextButton(
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
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                enabled = !isCreating,
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}
