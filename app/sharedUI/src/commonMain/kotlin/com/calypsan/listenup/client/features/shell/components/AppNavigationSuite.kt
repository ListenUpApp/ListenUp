@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.calypsan.listenup.client.features.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.shell.ShellDestination
import com.calypsan.listenup.client.features.shell.ShellNavType
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.brand_mark
import listenup.composeapp.generated.resources.common_listenup
import listenup.composeapp.generated.resources.shell_logout

private val RailWidth = 100.dp
private val BrandTile = 52.dp

/**
 * The shell's adaptive navigation surface, styled to the ListenUp M3 Expressive design.
 *
 * Renders an expressive [ShortNavigationBar] on compact widths, or a [NavigationRail] on wider
 * widths (the same icon-over-label rail for both medium and expanded — the design has a single
 * rail look, no collapse/expand). The rail carries a brand mark header, the primary destinations
 * with a coral selected-indicator, and a Logout action pinned to the bottom. Secondary actions
 * (settings, admin, profile) live in the top-bar account menu, not here.
 *
 * @param navType which surface to render for the current window size
 * @param currentDestination the selected destination (null guarded to Home)
 * @param onDestinationSelected invoked when a destination is tapped
 * @param onSignOut invoked when the rail's Logout action is tapped
 * @param modifier optional modifier
 */
@Composable
fun AppNavigationSuite(
    navType: ShellNavType,
    currentDestination: ShellDestination?,
    onDestinationSelected: (ShellDestination) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeDestination = currentDestination ?: ShellDestination.Home

    when (navType) {
        ShellNavType.BottomBar -> {
            ShortNavigationBar(
                modifier = modifier,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
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
                        colors =
                            ShortNavigationBarItemDefaults.colors(
                                selectedIndicatorColor = MaterialTheme.colorScheme.primary,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                    )
                }
            }
        }

        ShellNavType.RailCollapsed, ShellNavType.RailExpanded -> {
            NavigationRail(
                modifier = modifier.width(RailWidth),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                header = { RailBrandMark() },
            ) {
                Spacer(Modifier.height(8.dp))
                ShellDestination.entries.forEach { destination ->
                    val selected = safeDestination == destination
                    NavigationRailItem(
                        selected = selected,
                        onClick = { onDestinationSelected(destination) },
                        icon = {
                            Icon(
                                imageVector = if (selected) destination.selectedIcon else destination.icon,
                                contentDescription = destination.title,
                            )
                        },
                        label = { Text(destination.title) },
                        colors = railItemColors(),
                    )
                }
                Spacer(Modifier.weight(1f))
                NavigationRailItem(
                    selected = false,
                    onClick = onSignOut,
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = stringResource(Res.string.shell_logout),
                        )
                    },
                    label = { Text(stringResource(Res.string.shell_logout)) },
                    colors = railItemColors(),
                )
            }
        }
    }
}

@Composable
private fun railItemColors() =
    NavigationRailItemDefaults.colors(
        indicatorColor = MaterialTheme.colorScheme.primary,
        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/** The coral brand tile + wordmark shown at the top of the rail. */
@Composable
private fun RailBrandMark() {
    Column(
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(BrandTile)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = brandPainter(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            text = stringResource(Res.string.common_listenup),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun brandPainter(): Painter = painterResource(Res.drawable.brand_mark)
