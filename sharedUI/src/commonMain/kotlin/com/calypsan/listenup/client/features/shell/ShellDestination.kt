package com.calypsan.listenup.client.features.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Destinations for the main app shell bottom navigation.
 *
 * Each destination represents a top-level section of the app:
 * - Home: Personal landing page with continue listening, up next, stats
 * - Library: Browse full audiobook collection
 * - Discover: Social features and recommendations
 */
sealed class ShellDestination(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    data object Home : ShellDestination(
        route = "home",
        title = "Home",
        icon = Icons.Outlined.Home,
        selectedIcon = Icons.Filled.Home,
    )

    data object Library : ShellDestination(
        route = "library",
        title = "Library",
        icon = Icons.AutoMirrored.Outlined.LibraryBooks,
        selectedIcon = Icons.AutoMirrored.Filled.LibraryBooks,
    )

    data object Discover : ShellDestination(
        route = "discover",
        title = "Discover",
        icon = Icons.Outlined.Explore,
        selectedIcon = Icons.Filled.Explore,
    )

    companion object {
        // Use lazy to ensure nested objects are initialized before accessing
        val entries: List<ShellDestination> by lazy {
            listOf(Home, Library, Discover)
        }
    }
}
