package com.calypsan.listenup.client.features.shell.components

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.shell.ShellDestination

/**
 * Height of the navigation bar content - used for positioning elements above it.
 * Does not include system gesture bar insets.
 */
val NavigationBarHeight = 80.dp

/**
 * Bottom navigation bar for the app shell.
 *
 * Displays three destinations: Home, Library, Discover.
 * Uses Material 3 NavigationBar with outlined/filled icon states.
 *
 * @param currentDestination The currently selected destination
 * @param onDestinationSelected Callback when a destination is selected
 * @param modifier Optional modifier
 */
@Composable
fun AppNavigationBar(
    currentDestination: ShellDestination?,
    onDestinationSelected: (ShellDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Guard against null destination during recomposition transitions
    val safeDestination = currentDestination ?: ShellDestination.Home

    NavigationBar(modifier = modifier) {
        ShellDestination.entries.forEach { destination ->
            val selected = safeDestination == destination

            NavigationBarItem(
                selected = selected,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                        contentDescription = destination.title,
                    )
                },
                label = { Text(destination.title) },
            )
        }
    }
}
