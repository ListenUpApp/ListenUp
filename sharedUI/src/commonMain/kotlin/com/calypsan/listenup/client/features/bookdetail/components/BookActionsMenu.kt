package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_add_to_collection
import listenup.composeapp.generated.resources.book_detail_add_to_shelf
import listenup.composeapp.generated.resources.book_detail_edit_book
import listenup.composeapp.generated.resources.book_detail_mark_as_finished
import listenup.composeapp.generated.resources.book_detail_mark_as_not_started
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.common_share
import listenup.composeapp.generated.resources.metadata_match_on_audible
import org.jetbrains.compose.resources.stringResource

/**
 * Dropdown menu for book actions.
 * Shows Edit, Find Metadata, Mark as Complete, Mark as Not Started, Add to Shelf.
 * Delete is shown only for admin users.
 *
 * @param expanded Whether the menu is currently showing
 * @param onDismiss Called when the menu should be dismissed
 * @param isComplete Whether the book is marked as complete
 * @param hasProgress Whether the book has any playback progress
 * @param isAdmin Whether the current user is an admin
 * @param onEditClick Called when Edit Book is clicked
 * @param onFindMetadataClick Called when Find Metadata is clicked
 * @param onMarkCompleteClick Called when Mark as Complete is clicked (shown only when not complete)
 * @param onMarkNotStartedClick Called when Mark as Not Started is clicked (shown when there is progress or it is complete)
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
    onMarkNotStartedClick: () -> Unit,
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
            text = { Text(stringResource(Res.string.metadata_match_on_audible)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                )
            },
            onClick = onFindMetadataClick,
        )

        HorizontalDivider()

        // Mark as Complete (only when not already complete)
        if (!isComplete) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.book_detail_mark_as_finished)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                    )
                },
                onClick = onMarkCompleteClick,
            )
        }

        // Mark as Not Started (whenever there is progress or it is complete — i.e. something to clear)
        if (hasProgress || isComplete) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.book_detail_mark_as_not_started)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                    )
                },
                onClick = onMarkNotStartedClick,
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

        // Delete Book (admin only) — not yet implemented
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
                enabled = false, // Not yet implemented
            )
        }
    }
}
