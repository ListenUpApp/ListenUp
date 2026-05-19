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
import androidx.compose.material.icons.filled.FilterList
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.util.parseHexColor
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.domain.model.Shelf
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_add_to_shelf
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.library_create_add
import listenup.composeapp.generated.resources.library_create_new_shelf
import listenup.composeapp.generated.resources.common_shelf_name_hint
import listenup.composeapp.generated.resources.library_shelf_name
import listenup.composeapp.generated.resources.library_you_dont_have_any_shelves

/**
 * Bottom sheet for selecting a shelf to add books to.
 *
 * Used in the library multi-select flow for users to add selected books
 * to one of their personal shelves. Also supports creating a new shelf inline.
 *
 * @param shelves List of user's shelves
 * @param selectedBookCount Number of books that will be added
 * @param onShelfSelected Called when a shelf is tapped
 * @param onCreateAndAddToShelf Called to create a new shelf and add books to it
 * @param onDismiss Called when the sheet is dismissed
 * @param isLoading Whether an add operation is in progress
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfPickerSheet(
    shelves: List<Shelf>,
    selectedBookCount: Int,
    onShelfSelected: (String) -> Unit,
    onCreateAndAddToShelf: (name: String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
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
                    text = stringResource(Res.string.book_detail_add_to_shelf),
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

            // Content
            Box(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Create new shelf option (always shown first)
                    item(key = "create_new") {
                        CreateNewShelfRow(
                            onClick = { showCreateDialog = true },
                            enabled = !isLoading,
                        )
                        if (shelves.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 24.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }

                    // Existing shelves
                    if (shelves.isEmpty()) {
                        item(key = "empty_hint") {
                            Text(
                                text = stringResource(Res.string.library_you_dont_have_any_shelves),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            )
                        }
                    } else {
                        items(
                            items = shelves,
                            key = { it.id },
                        ) { shelf ->
                            ShelfRow(
                                shelf = shelf,
                                onClick = { onShelfSelected(shelf.id) },
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

            Spacer(Modifier.height(16.dp))
        }
    }

    // Create shelf dialog
    if (showCreateDialog) {
        CreateShelfDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                onCreateAndAddToShelf(name)
            },
        )
    }
}

/**
 * Row for creating a new shelf.
 */
@Composable
private fun CreateNewShelfRow(
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
                text = stringResource(Res.string.library_create_new_shelf),
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
 * A single shelf row in the picker.
 */
@Composable
private fun ShelfRow(
    shelf: Shelf,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    // Parse the owner's avatar color for the shelf icon background
    val iconColor =
        remember(shelf.ownerAvatarColor) {
            parseHexColor(shelf.ownerAvatarColor)
        }

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
            // Shelf icon with avatar color background
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shelf.name,
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
                        if (shelf.bookCount == 1) {
                            "1 book"
                        } else {
                            "${shelf.bookCount} books"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Dialog for creating a new shelf.
 */
@Composable
private fun CreateShelfDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var shelfName by remember { mutableStateOf("") }
    val isValid = shelfName.isNotBlank()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // Short delay to allow dialog to fully render before requesting focus
        kotlinx.coroutines.delay(100)
        focusRequester.requestFocus()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(Res.string.library_create_new_shelf)) },
        text = {
            ListenUpTextField(
                value = shelfName,
                onValueChange = { shelfName = it },
                label = stringResource(Res.string.library_shelf_name),
                placeholder = stringResource(Res.string.common_shelf_name_hint),
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(shelfName.trim()) },
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
