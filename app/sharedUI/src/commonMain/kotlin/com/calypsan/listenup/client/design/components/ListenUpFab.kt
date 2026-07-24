package com.calypsan.listenup.client.design.components

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Standard ListenUp icon-only floating action button.
 *
 * Always visible — uses color to communicate enabled/disabled state
 * rather than showing/hiding the FAB.
 *
 * @param onClick Callback when FAB is clicked (only fires when [enabled])
 * @param icon Icon to display
 * @param contentDescription Accessibility description
 * @param enabled Whether the FAB is actionable (disabled uses surfaceVariant colors)
 */
@Composable
fun ListenUpFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
) {
    FloatingActionButton(
        onClick = { if (enabled) onClick() },
        containerColor =
            if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint =
                if (enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/**
 * Extended ListenUp FAB with text label — used for save/action buttons.
 *
 * Always visible — uses color to communicate enabled/disabled state.
 *
 * @param onClick Callback when FAB is clicked (only fires when [enabled])
 * @param icon Icon to display
 * @param text Label text
 * @param enabled Whether the FAB is actionable
 * @param isLoading Whether to show a loading indicator instead of the icon
 */
@Composable
fun ListenUpExtendedFab(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    enabled: Boolean = true,
    isLoading: Boolean = false,
) {
    val contentColor =
        if (enabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }

    ExtendedFloatingActionButton(
        onClick = { if (enabled && !isLoading) onClick() },
        icon = {
            if (isLoading) {
                ListenUpLoadingIndicatorSmall(color = contentColor)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        },
        text = {
            Text(
                text = text,
                color = contentColor,
            )
        },
        expanded = true,
        containerColor =
            if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
    )
}
