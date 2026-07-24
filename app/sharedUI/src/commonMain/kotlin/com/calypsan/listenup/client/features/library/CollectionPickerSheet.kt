package com.calypsan.listenup.client.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.model.Collection
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_add_to_collection
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_collection_name_hint
import listenup.composeapp.generated.resources.library_collection_name
import listenup.composeapp.generated.resources.library_create_add
import listenup.composeapp.generated.resources.library_create_a_collection_in_the
import listenup.composeapp.generated.resources.library_create_new_collection
import listenup.composeapp.generated.resources.common_no_items_yet

/**
 * Bottom sheet for selecting a collection to add books to.
 *
 * Used in the library multi-select flow for admins to add selected books
 * to an existing collection. Admins may also create a new collection inline.
 *
 * Collections are admin-managed: the picker is only presented to admins, and the
 * inline create affordance is additionally gated on [canCreate] as defense-in-depth.
 *
 * @param collections List of available collections
 * @param selectedBookCount Number of books that will be added
 * @param onCollectionSelected Called when a collection is tapped
 * @param onCreateAndAddToCollection Called to create a new collection and add books to it
 * @param onDismiss Called when the sheet is dismissed
 * @param isLoading Whether an add operation is in progress
 * @param canCreate Whether the inline "create new collection" affordance is shown (admin-only)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionPickerSheet(
    collections: List<Collection>,
    selectedBookCount: Int,
    onCollectionSelected: (String) -> Unit,
    onCreateAndAddToCollection: (name: String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    canCreate: Boolean = false,
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            // Standard drag handle with proper spacing
            Surface(
                modifier =
                    Modifier
                        .padding(vertical = 12.dp)
                        .width(32.dp)
                        .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            ) {}
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
        ) {
            // Header
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.book_detail_add_to_collection),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text =
                        if (selectedBookCount == 1) {
                            "1 book selected"
                        } else {
                            "$selectedBookCount books selected"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            // Loading overlay or collection list
            CollectionPickerContent(
                collections = collections,
                isLoading = isLoading,
                canCreate = canCreate,
                onCollectionSelected = onCollectionSelected,
                onCreateClick = { showCreateDialog = true },
                modifier = Modifier.weight(1f, fill = false),
            )

            Spacer(Modifier.height(16.dp))
        }
    }

    // Create collection dialog (only reachable when canCreate gates the create row)
    if (showCreateDialog) {
        CreateCollectionDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                onCreateAndAddToCollection(name)
            },
        )
    }
}

/**
 * Scrollable list body: the optional admin create row, then the collection rows
 * (or the empty state), overlaid by a loading indicator while an add is in flight.
 */
@Composable
private fun CollectionPickerContent(
    collections: List<Collection>,
    isLoading: Boolean,
    canCreate: Boolean,
    onCollectionSelected: (String) -> Unit,
    onCreateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Create new collection option (admin-only, always shown first when present)
            if (canCreate) {
                item(key = "create_new") {
                    CreateNewCollectionRow(
                        onClick = onCreateClick,
                        enabled = !isLoading,
                    )
                    if (collections.isNotEmpty()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }

            if (collections.isEmpty()) {
                item(key = "empty_hint") {
                    CollectionPickerEmptyState(showCreateHint = !canCreate)
                }
            } else {
                items(
                    items = collections,
                    key = { it.id },
                ) { collection ->
                    CollectionRow(
                        collection = collection,
                        onClick = { onCollectionSelected(collection.id) },
                        enabled = !isLoading,
                    )
                }
            }
        }

        // Loading overlay
        if (isLoading) {
            Surface(
                modifier = Modifier.matchParentSize(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    ListenUpLoadingIndicator()
                }
            }
        }
    }
}

/**
 * Empty-state body shown when there are no collections yet.
 *
 * @param showCreateHint Whether to point the user at the Admin section to create one
 *   (shown only when the inline create affordance is unavailable to this user).
 */
@Composable
private fun CollectionPickerEmptyState(showCreateHint: Boolean) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.common_no_items_yet, "collections"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showCreateHint) {
            Text(
                text = stringResource(Res.string.library_create_a_collection_in_the),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Row for creating a new collection.
 */
@Composable
private fun CreateNewCollectionRow(
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Plus icon
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = stringResource(Res.string.library_create_new_collection),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    },
            )
        }
    }
}

/**
 * A single collection row in the picker.
 */
@Composable
private fun CollectionRow(
    collection: Collection,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = collection.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        if (collection.bookCount == 1) {
                            "1 book"
                        } else {
                            "${collection.bookCount} books"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Dialog for creating a new collection.
 */
@Composable
private fun CreateCollectionDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var collectionName by remember { mutableStateOf("") }
    val isValid = collectionName.isNotBlank()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // Short delay to allow dialog to fully render before requesting focus
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(Res.string.library_create_new_collection)) },
        text = {
            ListenUpTextField(
                value = collectionName,
                onValueChange = { collectionName = it },
                label = stringResource(Res.string.library_collection_name),
                placeholder = stringResource(Res.string.common_collection_name_hint),
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(collectionName.trim()) },
                enabled = isValid,
            ) {
                Text(stringResource(Res.string.library_create_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}
