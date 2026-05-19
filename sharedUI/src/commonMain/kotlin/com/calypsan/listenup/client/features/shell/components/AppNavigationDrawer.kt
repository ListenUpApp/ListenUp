package com.calypsan.listenup.client.features.shell.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.features.shell.ShellDestination
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_administration
import listenup.composeapp.generated.resources.common_settings

/**
 * Permanent navigation drawer for expanded screens (landscape tablets, desktop).
 *
 * Always visible alongside content. Displays navigation destinations with
 * the user avatar positioned at the bottom of the drawer.
 *
 * @param currentDestination The currently selected destination
 * @param onDestinationSelected Callback when a destination is selected
 * @param user Current user entity for avatar display
 * @param isAvatarMenuExpanded Whether avatar dropdown is expanded
 * @param onAvatarMenuExpandedChange Callback when avatar menu expand state changes
 * @param onAdminClick Callback when administration is clicked (only shown for admin users)
 * @param onSettingsClick Callback when settings is clicked
 * @param onSignOutClick Callback when sign out is clicked
 * @param modifier Optional modifier
 * @param content The main content area
 */
@Composable
fun AppNavigationDrawer(
    currentDestination: ShellDestination?,
    onDestinationSelected: (ShellDestination) -> Unit,
    user: User?,
    isAvatarMenuExpanded: Boolean,
    onAvatarMenuExpandedChange: (Boolean) -> Unit,
    onAdminClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onMyProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val safeDestination = currentDestination ?: ShellDestination.Home

    PermanentNavigationDrawer(
        modifier = modifier,
        drawerContent = {
            PermanentDrawerSheet(
                modifier = Modifier.width(240.dp),
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // Navigation destinations
                ShellDestination.entries.forEach { destination ->
                    val selected = safeDestination == destination

                    NavigationDrawerItem(
                        selected = selected,
                        onClick = { onDestinationSelected(destination) },
                        icon = {
                            Icon(
                                imageVector = if (selected) destination.selectedIcon else destination.icon,
                                contentDescription = destination.title,
                            )
                        },
                        label = { Text(destination.title) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }

                // Push bottom items down
                Spacer(modifier = Modifier.weight(1f))

                // Settings
                NavigationDrawerItem(
                    selected = false,
                    onClick = onSettingsClick,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(Res.string.common_settings),
                        )
                    },
                    label = { Text(stringResource(Res.string.common_settings)) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )

                // Administration (admin users only)
                if (onAdminClick != null) {
                    NavigationDrawerItem(
                        selected = false,
                        onClick = onAdminClick,
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.AdminPanelSettings,
                                contentDescription = stringResource(Res.string.common_administration),
                            )
                        },
                        label = { Text(stringResource(Res.string.common_administration)) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // User avatar at bottom (profile + sign out only)
                UserAvatar(
                    user = user,
                    expanded = isAvatarMenuExpanded,
                    onExpandedChange = onAvatarMenuExpandedChange,
                    onMyProfileClick = onMyProfileClick,
                    onSignOutClick = onSignOutClick,
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
                )
            }
        },
        content = content,
    )
}
