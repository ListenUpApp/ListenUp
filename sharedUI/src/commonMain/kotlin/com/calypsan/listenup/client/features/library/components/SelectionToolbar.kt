package com.calypsan.listenup.client.features.library.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.library_collection
import listenup.composeapp.generated.resources.library_exit_selection_mode
import listenup.composeapp.generated.resources.library_shelf

/**
 * Toolbar shown when in multi-select mode.
 * Displays the selected count and actions for the selection.
 *
 * Actions:
 * - "Add to Shelf" - available to all users
 * - "Add to Collection" - available to admin users only
 *
 * @param selectedCount Number of currently selected books
 * @param onAddToShelf Called when "Add to Shelf" is tapped
 * @param onAddToCollection Called when "Add to Collection" is tapped (null = hide button)
 * @param onClose Called when the close button is tapped
 * @param modifier Optional modifier
 */
@Composable
fun SelectionToolbar(
    selectedCount: Int,
    onAddToShelf: () -> Unit,
    onAddToCollection: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Close button
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.library_exit_selection_mode),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            // Selected count
            Text(
                text =
                    if (selectedCount == 1) {
                        "1 selected"
                    } else {
                        "$selectedCount selected"
                    },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            Spacer(modifier = Modifier.weight(1f))

            // Add to shelf button (all users)
            SelectionActionButton(
                icon = Icons.Default.FilterList,
                label = stringResource(Res.string.library_shelf),
                enabled = selectedCount > 0,
                onClick = onAddToShelf,
            )

            // Add to collection button (admin only)
            if (onAddToCollection != null) {
                SelectionActionButton(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = stringResource(Res.string.library_collection),
                    enabled = selectedCount > 0,
                    onClick = onAddToCollection,
                )
            }
        }
    }
}

/**
 * A single labelled selection action (icon + text). Tint follows [enabled] so disabled actions
 * read as muted, matching the inline buttons it replaces.
 */
@Composable
private fun SelectionActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    TextButton(
        onClick = onClick,
        enabled = enabled,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = contentColor,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = contentColor,
        )
    }
}
