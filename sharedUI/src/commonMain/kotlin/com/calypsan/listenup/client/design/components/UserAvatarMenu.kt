
package com.calypsan.listenup.client.design.components

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.ServerConfig
import org.koin.compose.koinInject
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_administration
import listenup.composeapp.generated.resources.common_displayname_avatar
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
    val platformContext = LocalPlatformContext.current
    val serverConfig: ServerConfig = koinInject()
    val imageStorage: ImageStorage = koinInject()
    val serverUrl by produceState<String?>(null) {
        value = serverConfig.getServerUrl()?.value
    }

    val hasImageAvatar = user?.avatarType == "image" && !user.avatarValue.isNullOrEmpty()

    Box(modifier = modifier) {
        // Anchor: a labelled pill (name + email + chevron) on wide chrome, or the bare circle.
        if (showLabel && user != null) {
            UserAvatarPill(user = user, onClick = { onExpandedChange(true) })
        } else {
            UserAvatarCircle(
                user = user,
                hasImageAvatar = hasImageAvatar,
                serverUrl = serverUrl,
                imageStorage = imageStorage,
                platformContext = platformContext,
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
            UserAvatar(userId = user.id.value, size = AvatarSize.Small)
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
    hasImageAvatar: Boolean,
    serverUrl: String?,
    imageStorage: ImageStorage,
    platformContext: PlatformContext,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color =
            if (hasImageAvatar) {
                Color.Transparent
            } else {
                user?.let { Color.hsl((it.id.value.hashCode() and 0x7FFFFFFF).rem(360).toFloat(), 0.4f, 0.65f) }
                    ?: MaterialTheme.colorScheme.surfaceContainerHighest
            },
        modifier = Modifier.size(48.dp),
    ) {
        if (hasImageAvatar && user != null) {
            // Offline-first: prefer local cached avatar
            val localPath =
                if (imageStorage.userAvatarExists(user.id.value)) {
                    imageStorage.getUserAvatarPath(user.id.value)
                } else {
                    null
                }

            if (localPath != null) {
                // Cache key matches the canonical UserAvatar key so Coil shares the cache entry.
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(platformContext)
                            .data(localPath)
                            .memoryCacheKey("${user.id}-avatar")
                            .diskCacheKey("${user.id}-avatar")
                            .build(),
                    contentDescription = stringResource(Res.string.common_displayname_avatar, user.displayName),
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else if (serverUrl != null) {
                // Fallback: fetch from server with disabled caching
                val avatarUrl = "$serverUrl${user.avatarValue}"
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(platformContext)
                            .data(avatarUrl)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    contentDescription = stringResource(Res.string.common_displayname_avatar, user.displayName),
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // No local file and no server URL - show initials
                AvatarInitials(displayName = user.displayName)
            }
        } else {
            AvatarInitials(displayName = user?.displayName)
        }
    }
}

@Composable
private fun AvatarInitials(displayName: String?) {
    Box(contentAlignment = Alignment.Center) {
        Text(
            text =
                displayName?.let { name ->
                    name
                        .trim()
                        .split("\\s+".toRegex())
                        .let { parts ->
                            when {
                                parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
                                name.length >= 2 -> name.take(2)
                                else -> name.take(1)
                            }
                        }.uppercase()
                } ?: "?",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
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
