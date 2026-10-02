@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.calypsan.listenup.client.features.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.calypsan.listenup.client.design.components.CountBadge
import com.calypsan.listenup.client.features.admin.inbox.heldWaitingDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
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
import com.calypsan.listenup.client.design.components.LocalNowPlayingInsets
import com.calypsan.listenup.client.design.haptics.LocalHaptics
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
private val HELD_BADGE_MIN_SIZE = 18.dp
private val HELD_BADGE_RING_WIDTH = 2.dp
private const val HELD_BADGE_MAX_COUNT = 99

/**
 * The shell's adaptive navigation surface, styled to the ListenUp M3 Expressive design.
 *
 * Renders an expressive [ShortNavigationBar] on compact widths, or a [NavigationRail] on wider
 * widths (the same icon-over-label rail for both medium and expanded — the design has a single
 * rail look, no collapse/expand). The rail carries a brand mark header, the primary destinations
 * with a coral selected-indicator, and a Logout action pinned to the bottom. Secondary actions
 * (settings, admin, profile) live in the top-bar account menu, not here.
 *
 * On these widths the docked mini-player spans the full window bottom, under the rail, so the
 * rail's content ends above it ([LocalNowPlayingInsets]) — the same clearance the content pane
 * takes — and the pinned Logout is never hidden behind the bar.
 *
 * @param navType which surface to render for the current window size
 * @param currentDestination the selected destination (null guarded to Home)
 * @param onDestinationSelected invoked when a destination is tapped
 * @param onSignOutRequest invoked when the rail's Logout is tapped; it asks, it does not sign out
 *   (the shell's [SignOutConfirmation] confirms first)
 * @param libraryBadgeCount books held for review, shown on Library (admins only; 0 hides it)
 * @param modifier optional modifier
 */
@Composable
fun AppNavigationSuite(
    navType: ShellNavType,
    currentDestination: ShellDestination?,
    onDestinationSelected: (ShellDestination) -> Unit,
    onSignOutRequest: () -> Unit,
    libraryBadgeCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
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
                        onClick = {
                            haptics.press()
                            onDestinationSelected(destination)
                        },
                        icon = {
                            DestinationIcon(
                                destination = destination,
                                selected = selected,
                                badgeCount = if (destination == ShellDestination.Library) libraryBadgeCount else 0,
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
                windowInsets = NavigationRailDefaults.windowInsets.union(LocalNowPlayingInsets.current),
            ) {
                Spacer(Modifier.height(8.dp))
                ShellDestination.entries.forEach { destination ->
                    val selected = safeDestination == destination
                    NavigationRailItem(
                        selected = selected,
                        onClick = {
                            haptics.press()
                            onDestinationSelected(destination)
                        },
                        icon = {
                            DestinationIcon(
                                destination = destination,
                                selected = selected,
                                badgeCount = if (destination == ShellDestination.Library) libraryBadgeCount else 0,
                            )
                        },
                        label = { Text(destination.title) },
                        colors = railItemColors(),
                    )
                }
                Spacer(Modifier.weight(1f))
                NavigationRailItem(
                    selected = false,
                    onClick = {
                        haptics.press()
                        onSignOutRequest()
                    },
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

/** A destination's icon, wearing the held-books badge when [badgeCount] is above zero. */
@Composable
private fun DestinationIcon(
    destination: ShellDestination,
    selected: Boolean,
    badgeCount: Int,
) {
    val icon: @Composable () -> Unit = {
        Icon(
            imageVector = if (selected) destination.selectedIcon else destination.icon,
            // The item's visible label already names the destination; naming the icon too made a
            // screen reader say it twice ("Library, Library").
            contentDescription = null,
        )
    }
    if (badgeCount > 0) {
        BadgedBox(badge = { HeldCountBadge(badgeCount) }) { icon() }
    } else {
        icon()
    }
}

/**
 * The Library's held count: the house [CountBadge] in amber (tertiary / onTertiary) — "waiting for
 * you", never coral, which is "act here" and the selected indicator's colour. A 2dp ring in the bar's
 * own colour, drawn outside the pill, separates it from that indicator, against which amber has
 * too little lightness contrast on its own. Read as "3 books waiting for review", the true count; the drawn "99+" is not read.
 */
@Composable
private fun HeldCountBadge(count: Int) {
    val description = heldWaitingDescription(count)
    // The ring is a disc of the bar's colour behind the pill, 2dp wider on every side, so it frames
    // the amber rather than painting over its edge.
    Box(
        modifier =
            Modifier
                .clearAndSetSemantics { contentDescription = description }
                .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
                .padding(HELD_BADGE_RING_WIDTH),
    ) {
        CountBadge(
            count = count,
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            minSize = HELD_BADGE_MIN_SIZE,
            maxCount = HELD_BADGE_MAX_COUNT,
        )
    }
}

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
                    .clip(MaterialTheme.shapes.medium)
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
