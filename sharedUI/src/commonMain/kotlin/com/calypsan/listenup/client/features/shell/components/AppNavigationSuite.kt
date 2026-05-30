@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.calypsan.listenup.client.features.shell.components

import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.ShellNavType

/**
 * The shell's adaptive navigation surface.
 *
 * Renders an expressive [ShortNavigationBar] on compact widths, or a [WideNavigationRail]
 * (collapsed on medium, expanded on large) on wider widths. Carries only the primary
 * destinations — secondary actions (settings, admin, profile, sign-out) live in the
 * top-bar account menu, not here.
 *
 * The rail's collapsed/expanded form is driven purely from [navType] (window width); there
 * is no manual toggle.
 *
 * @param navType which surface to render for the current window size
 * @param currentDestination the selected destination (null guarded to Home)
 * @param onDestinationSelected invoked when a destination is tapped
 * @param modifier optional modifier
 */
@Composable
fun AppNavigationSuite(
    navType: ShellNavType,
    currentDestination: ShellDestination?,
    onDestinationSelected: (ShellDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeDestination = currentDestination ?: ShellDestination.Home

    when (navType) {
        ShellNavType.BottomBar -> {
            ShortNavigationBar(modifier = modifier) {
                ShellDestination.entries.forEach { destination ->
                    val selected = safeDestination == destination
                    ShortNavigationBarItem(
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

        ShellNavType.RailCollapsed, ShellNavType.RailExpanded -> {
            val railExpanded = navType == ShellNavType.RailExpanded
            val railState =
                rememberWideNavigationRailState(
                    initialValue = if (railExpanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed,
                )
            // Drive collapsed/expanded from window width only (no manual toggle).
            LaunchedEffect(railExpanded) {
                if (railExpanded) railState.expand() else railState.collapse()
            }
            WideNavigationRail(
                modifier = modifier,
                state = railState,
            ) {
                ShellDestination.entries.forEach { destination ->
                    val selected = safeDestination == destination
                    WideNavigationRailItem(
                        selected = selected,
                        onClick = { onDestinationSelected(destination) },
                        railExpanded = railExpanded,
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
    }
}
