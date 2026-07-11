package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_add_to_collection
import listenup.composeapp.generated.resources.book_detail_add_to_shelf
import listenup.composeapp.generated.resources.book_detail_campfire_this_book
import listenup.composeapp.generated.resources.campfire_listening_now_count
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.book_detail_edit_book
import listenup.composeapp.generated.resources.book_detail_find_metadata
import listenup.composeapp.generated.resources.book_detail_mark_as_complete
import listenup.composeapp.generated.resources.book_detail_mark_as_not_started
import listenup.composeapp.generated.resources.common_share

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
 * @param campfireLiveCount Current number of listeners already in a Campfire session for this
 * book. Zero renders the item with no badge; positive shows a "N listening" badge so a member
 * knows a session is already underway before tapping in.
 * @param onCampfireClick Called when "Campfire this book" is clicked — opens the create/join flow.
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
    campfireLiveCount: Int,
    onCampfireClick: () -> Unit,
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

        // Mark as Complete (only when not already complete)
        if (!isComplete) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.book_detail_mark_as_complete)) },
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

        // Campfire this book — the create/join entry point (moved off the book-detail overflow
        // FAB, which used to cover the top bar's own three-dot menu). A trailing badge surfaces
        // the live listener count when a session is already underway.
        CampfireMenuItem(campfireLiveCount = campfireLiveCount, onClick = onCampfireClick)

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

/**
 * "Campfire this book" menu item — extracted from [BookActionsMenu] to keep that function within
 * the length budget. Shows a "N listening" badge (reusing [Res.string.campfire_listening_now_count])
 * when [campfireLiveCount] is positive, tinting the fire icon and badge with
 * [MaterialTheme.colorScheme.primary] so a live session reads as active at a glance.
 */
@Composable
private fun CampfireMenuItem(
    campfireLiveCount: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.book_detail_campfire_this_book))
                if (campfireLiveCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = stringResource(Res.string.campfire_listening_now_count, campfireLiveCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint =
                    if (campfireLiveCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        },
        onClick = onClick,
    )
}
