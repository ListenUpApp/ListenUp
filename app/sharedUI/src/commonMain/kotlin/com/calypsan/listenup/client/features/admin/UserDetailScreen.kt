package com.calypsan.listenup.client.features.admin

import com.calypsan.listenup.client.design.components.ListenUpTopAppBar
import com.calypsan.listenup.client.design.components.SectionColumns
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.SegmentedGroup
import com.calypsan.listenup.client.design.components.SettingRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.presentation.admin.UserDetailViewModel
import com.calypsan.listenup.client.presentation.error.localized
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_role_and_permissions
import listenup.composeapp.generated.resources.admin_role_owner
import listenup.composeapp.generated.resources.admin_protected_user
import listenup.composeapp.generated.resources.admin_this_users_permissions_cannot_be
import listenup.composeapp.generated.resources.common_admin
import listenup.composeapp.generated.resources.common_display_name
import listenup.composeapp.generated.resources.common_email_address
import listenup.composeapp.generated.resources.common_entity_information
import listenup.composeapp.generated.resources.common_member
import listenup.composeapp.generated.resources.common_permissions
import listenup.composeapp.generated.resources.common_role
import org.jetbrains.compose.resources.stringResource
import com.calypsan.listenup.client.design.theme.HeroInk

/**
 * Screen for viewing a single user's details, and the way into their role and permissions.
 *
 * Features:
 * - View user information (name, email, role)
 * - "Role and permissions · Contributor" opens the permissions screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(
    viewModel: UserDetailViewModel,
    onBackClick: () -> Unit,
    onPermissionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ListenUpTopAppBar(
                title = (state as? UserDetailUiState.Ready)?.run { user.displayableName } ?: "User Details",
                onBack = onBackClick,
            )
        },
    ) { innerPadding ->
        UserDetailBody(
            state = state,
            innerPadding = innerPadding,
            onPermissionsClick = onPermissionsClick,
        )
    }
}

@Composable
private fun UserDetailBody(
    state: UserDetailUiState,
    innerPadding: PaddingValues,
    onPermissionsClick: () -> Unit,
) {
    when (state) {
        is UserDetailUiState.Loading -> {
            FullScreenLoadingIndicator()
        }

        is UserDetailUiState.Error -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error.localized(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is UserDetailUiState.Ready -> {
            UserDetailContent(
                state = state,
                onPermissionsClick = onPermissionsClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * The screen's loaded body, hosted by its scaffold. A phone stacks who the user is over what they may
 * do. From the medium width up the two sit side by side in [SectionColumns], so an admin reads the
 * person and their permissions in one glance.
 */
@Composable
internal fun UserDetailContent(
    state: UserDetailUiState.Ready,
    onPermissionsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWide =
        currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    if (isWide) {
        SectionColumns(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin, vertical = 16.dp),
        ) {
            section {
                Column {
                    SectionHeading(stringResource(Res.string.common_entity_information, "User"))
                    UserInfoCard(user = state.user)
                }
            }
            section {
                Column {
                    SectionHeading(stringResource(Res.string.common_permissions))
                    AccessRow(state = state, onClick = onPermissionsClick)
                }
            }
        }
    } else {
        LazyColumn(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.screenMargin),
        ) {
            // User info section
            item {
                SectionHeading(
                    text = stringResource(Res.string.common_entity_information, "User"),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item {
                UserInfoCard(user = state.user)
            }

            // Permissions section
            item {
                Spacer(modifier = Modifier.height(Spacing.xl))
                SectionHeading(stringResource(Res.string.common_permissions))
            }

            item {
                AccessRow(state = state, onClick = onPermissionsClick)
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

/**
 * "Role and permissions · Contributor": what this user can do at a glance, and the way into changing it.
 * A protected user also gets the notice saying why their role and flags are locked.
 */
@Composable
private fun AccessRow(
    state: UserDetailUiState.Ready,
    onClick: () -> Unit,
) {
    Column {
        SegmentedGroup {
            SettingRow(
                icon = Icons.Outlined.Shield,
                title = stringResource(Res.string.admin_role_and_permissions),
                subtitle = state.user.access.title(),
                onClick = onClick,
            )
        }
        if (state.user.isProtected) {
            Spacer(modifier = Modifier.height(16.dp))
            ProtectedUserNotice()
        }
    }
}

@Composable
private fun UserInfoCard(
    user: AdminUserInfo,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Name row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    Text(
                        text = user.displayableName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.common_display_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Email row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Email,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    Text(
                        text = user.email,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.common_email_address),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Role row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint =
                        if (user.isRoot || user.role.equals("admin", ignoreCase = true)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Column {
                    Text(
                        text =
                            when {
                                user.isRoot -> stringResource(Res.string.admin_role_owner)
                                user.role.equals("admin", ignoreCase = true) -> stringResource(Res.string.common_admin)
                                else -> stringResource(Res.string.common_member)
                            },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.common_role),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtectedUserNotice(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column {
                Text(
                    text = stringResource(Res.string.admin_protected_user),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = stringResource(Res.string.admin_this_users_permissions_cannot_be),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        HeroInk.muted(
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                )
            }
        }
    }
}
