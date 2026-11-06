package com.calypsan.listenup.client.features.shell

import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.graphics.vector.ImageVector
import com.calypsan.listenup.client.design.icons.ListenUpIcons

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
        icon = ListenUpIcons.Home,
        selectedIcon = ListenUpIcons.HomeFilled,
    )

    data object Library : ShellDestination(
        route = "library",
        title = "Library",
        icon = ListenUpIcons.LibraryBooks,
        selectedIcon = ListenUpIcons.LibraryBooksFilled,
    )

    data object Discover : ShellDestination(
        route = "discover",
        title = "Discover",
        icon = ListenUpIcons.Explore,
        selectedIcon = ListenUpIcons.ExploreFilled,
    )

    companion object {
        // Use lazy to ensure nested objects are initialized before accessing
        val entries: List<ShellDestination> by lazy {
            listOf(Home, Library, Discover)
        }
    }
}

/** Saves a [ShellDestination] by its stable [ShellDestination.route] so the selected
 *  shell tab survives configuration changes via `rememberSaveable`. */
val shellDestinationSaver: Saver<ShellDestination, String> =
    Saver(
        save = { it.route },
        restore = { route -> ShellDestination.entries.first { it.route == route } },
    )
