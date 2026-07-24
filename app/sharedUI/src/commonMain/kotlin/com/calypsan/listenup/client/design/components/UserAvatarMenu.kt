
package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.User
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_administration
import listenup.composeapp.generated.resources.common_loading
import listenup.composeapp.generated.resources.common_my_profile
import listenup.composeapp.generated.resources.common_settings
import listenup.composeapp.generated.resources.common_sign_out

/**
 * Circular avatar for the logged-in user with a dropdown menu.
 *
 * Used in the shell top bar to give the current user access to profile,
 * settings, administration, and sign-out actions.
 *
 * For displaying any user's avatar without a menu, use [UserAvatar] instead.
 *
 * @param user The current user entity (null shows placeholder)
 * @param expanded Whether the dropdown menu is expanded
 * @param onExpandedChange Callback when expanded state changes
 * @param onMyProfileClick Callback when My Profile is clicked
 * @param onAdminClick Callback when Administration is clicked (only shown for admin users)
 * @param onSettingsClick Callback when Settings is clicked
 * @param onSignOutClick Callback when Sign out is clicked
 * @param modifier Optional modifier
 */
@Composable
fun UserAvatarMenu(
    user: User?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onMyProfileClick: () -> Unit,
    onAdminClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    Box(modifier = modifier) {
        // Anchor: a labelled pill (name + email + chevron) on wide chrome, or the bare circle.
        if (showLabel && user != null) {
            UserAvatarPill(user = user, onClick = { onExpandedChange(true) })
        } else {
            UserAvatarCircle(
                user = user,
                onClick = { onExpandedChange(true) },
            )
        }

        // Dropdown menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            UserAvatarMenuContent(
                user = user,
                onExpandedChange = onExpandedChange,
                onMyProfileClick = onMyProfileClick,
                onAdminClick = onAdminClick,
                onSettingsClick = onSettingsClick,
                onSignOutClick = onSignOutClick,
            )
        }
    }
}

/**
 * Labelled avatar anchor for wide chrome (top bar): a tonal pill showing the avatar, the user's
 * display name and email, and an expand chevron. The whole pill is the click target.
 */
@Composable
private fun UserAvatarPill(
    user: User,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(userId = user.id.value, size = AvatarSize.Small, fallbackName = user.displayName)
            Column {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = user.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UserAvatarCircle(
    user: User?,
    onClick: () -> Unit,
) {
    if (user != null) {
        UserAvatar(
            userId = user.id.value,
            size = AvatarSize.Medium,
            onClick = onClick,
            fallbackName = user.displayName,
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(AvatarSize.Medium.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onClick),
        ) {
            Text(
                text = "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UserAvatarMenuContent(
    user: User?,
    onExpandedChange: (Boolean) -> Unit,
    onMyProfileClick: () -> Unit,
    onAdminClick: (() -> Unit)?,
    onSettingsClick: (() -> Unit)?,
    onSignOutClick: () -> Unit,
) {
    // Header with user info (non-clickable)
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (user != null) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = user.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Loading state - user data not yet available
            Text(
                text = stringResource(Res.string.common_loading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    HorizontalDivider()

    // My Profile menu item
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.common_my_profile)) },
        leadingIcon = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
        onClick = {
            onExpandedChange(false)
            onMyProfileClick()
        },
    )

    // Administration menu item - only shown for admin users
    if (user?.isAdmin == true && onAdminClick != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.common_administration)) },
            leadingIcon = { Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null) },
            onClick = {
                onExpandedChange(false)
                onAdminClick()
            },
        )
    }

    if (onSettingsClick != null) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.common_settings)) },
            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            onClick = {
                onExpandedChange(false)
                onSettingsClick()
            },
        )
    }

    DropdownMenuItem(
        text = { Text(stringResource(Res.string.common_sign_out)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
        onClick = {
            onExpandedChange(false)
            onSignOutClick()
        },
    )
}
