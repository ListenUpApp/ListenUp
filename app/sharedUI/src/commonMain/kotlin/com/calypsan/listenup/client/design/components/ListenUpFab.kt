package com.calypsan.listenup.client.design.components

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.haptics.LocalHaptics

/**
 * Standard ListenUp icon-only floating action button — the screen's one primary creation action
 * (create a backup, add a genre, create a collection).
 *
 * It has no disabled state, by design: a FAB that cannot act should not be on screen. Callers hide
 * it (don't compose it, or wrap it in `AnimatedVisibility`) when there is nothing to do. Committing
 * an edit is not a FAB's job; edit screens use [SaveAction] in the top bar.
 *
 * @param onClick Callback when the FAB is clicked.
 * @param icon Icon to display.
 * @param contentDescription Accessibility description.
 */
@Composable
fun ListenUpFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
) {
    val haptics = LocalHaptics.current
    FloatingActionButton(
        onClick = {
            haptics.press()
            onClick()
        },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}
