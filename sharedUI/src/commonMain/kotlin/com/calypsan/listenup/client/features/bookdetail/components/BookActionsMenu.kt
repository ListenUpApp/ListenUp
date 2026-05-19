package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_add_to_collection
import listenup.composeapp.generated.resources.book_detail_add_to_shelf
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.book_detail_discard_progress
import listenup.composeapp.generated.resources.book_detail_edit_book
import listenup.composeapp.generated.resources.book_detail_find_metadata
import listenup.composeapp.generated.resources.common_share

/**
 * Dropdown menu for book actions.
 * Shows Edit, Find Metadata, Mark Complete, Discard Progress, Add to Shelf.
 * Delete is shown only for admin users.
 *
 * @param expanded Whether the menu is currently showing
 * @param onDismiss Called when the menu should be dismissed
 * @param isComplete Whether the book is marked as complete
 * @param hasProgress Whether the book has any playback progress
 * @param isAdmin Whether the current user is an admin
 * @param onEditClick Called when Edit Book is clicked
 * @param onFindMetadataClick Called when Find Metadata is clicked
 * @param onMarkCompleteClick Called when Mark as Complete/Not Started is clicked
 * @param onDiscardProgressClick Called when Discard Progress is clicked
 * @param onAddToShelfClick Called when Add to Shelf is clicked
 * @param onAddToCollectionClick Called when Add to Collection is clicked (admin only)
 * @param onDeleteClick Called when Delete Book is clicked (admin only)
 */
@Suppress("LongParameterList")
@Composable
fun BookActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isComplete: Boolean,
    hasProgress: Boolean,
    isAdmin: Boolean,
    onEditClick: () -> Unit,
    onFindMetadataClick: () -> Unit,
    onMarkCompleteClick: () -> Unit,
    onDiscardProgressClick: () -> Unit,
    onAddToShelfClick: () -> Unit,
    onAddToCollectionClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        // Edit Book
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.book_detail_edit_book)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                )
            },
            onClick = onEditClick,
        )

        // Find Metadata
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.book_detail_find_metadata)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                )
            },
            onClick = onFindMetadataClick,
        )

        HorizontalDivider()

        // Mark as Complete / Not Started
        DropdownMenuItem(
            text = {
                Text(if (isComplete) "Mark as Not Started" else "Mark as Complete")
            },
            leadingIcon = {
                Icon(
                    imageVector =
                        if (isComplete) {
                            Icons.Default.RadioButtonUnchecked
                        } else {
                            Icons.Default.CheckCircle
                        },
                    contentDescription = null,
                )
            },
            onClick = onMarkCompleteClick,
        )

        // Discard Progress (only when there is progress to discard)
        if (hasProgress) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.book_detail_discard_progress)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = null,
                    )
                },
                onClick = onDiscardProgressClick,
            )
        }

        // Add to Shelf
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.book_detail_add_to_shelf)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                )
            },
            onClick = onAddToShelfClick,
        )

        // Add to Collection (admin only)
        if (isAdmin) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.book_detail_add_to_collection)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = null,
                    )
                },
                onClick = onAddToCollectionClick,
            )
        }

        // Share
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.common_share)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                )
            },
            onClick = onShareClick,
        )

        // Delete Book (admin only, stubbed for now)
        if (isAdmin) {
            HorizontalDivider()

            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Res.string.common_delete_name, "Book"),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                    )
                },
                colors =
                    MenuDefaults.itemColors(
                        textColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                onClick = onDeleteClick,
                enabled = false, // Stubbed for now
            )
        }
    }
}
