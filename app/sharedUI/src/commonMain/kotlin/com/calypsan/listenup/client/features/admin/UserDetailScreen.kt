package com.calypsan.listenup.client.features.admin

import com.calypsan.listenup.client.design.components.SectionColumns
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.calypsan.listenup.client.design.components.SegmentedGroup
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.SettingToggleRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.presentation.admin.UserDetailViewModel
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.error.localizedString
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_allow_sharing_collections_with_other
import listenup.composeapp.generated.resources.admin_allow_editing_content_metadata
import listenup.composeapp.generated.resources.admin_can_edit
import listenup.composeapp.generated.resources.admin_can_share
import listenup.composeapp.generated.resources.admin_protected_user
import listenup.composeapp.generated.resources.admin_this_users_permissions_cannot_be
import listenup.composeapp.generated.resources.common_display_name
import listenup.composeapp.generated.resources.common_email_address
import listenup.composeapp.generated.resources.common_entity_information
import listenup.composeapp.generated.resources.common_permissions
import listenup.composeapp.generated.resources.common_role
import org.jetbrains.compose.resources.stringResource
import com.calypsan.listenup.client.design.theme.HeroInk

/**
 * Screen for viewing and editing a single user's details and permissions.
 *
 * Features:
 * - View user information (name, email, role)
 * - Toggle the canEdit and canShare permissions
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreen(
    viewModel: UserDetailViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Transient mutation-failure error in snackbar (only meaningful in Ready).
    val readyError = (state as? UserDetailUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it.localizedString())
            viewModel.clearError()
        }
    }

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    val title =
                        (state as? UserDetailUiState.Ready)?.user?.displayableName
                            ?: "User Details"
                    Text(title, modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptics.press()
                            onBackClick()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        UserDetailBody(
            state = state,
            innerPadding = innerPadding,
            onToggleCanEdit = viewModel::toggleCanEdit,
            onToggleCanShare = viewModel::toggleCanShare,
        )
    }
}

@Composable
private fun UserDetailBody(
    state: UserDetailUiState,
    innerPadding: PaddingValues,
    onToggleCanEdit: () -> Unit,
    onToggleCanShare: () -> Unit,
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
                onToggleCanEdit = onToggleCanEdit,
                onToggleCanShare = onToggleCanShare,
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
    onToggleCanEdit: () -> Unit,
    onToggleCanShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isWide =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
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
                    PermissionsSection(
                        state = state,
                        onToggleCanEdit = onToggleCanEdit,
                        onToggleCanShare = onToggleCanShare,
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg),
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
                PermissionsSection(
                    state = state,
                    onToggleCanEdit = onToggleCanEdit,
                    onToggleCanShare = onToggleCanShare,
                )
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

/** The permission switches, and — for a protected user — the notice saying why they are locked. */
@Composable
private fun PermissionsSection(
    state: UserDetailUiState.Ready,
    onToggleCanEdit: () -> Unit,
    onToggleCanShare: () -> Unit,
) {
    Column {
        PermissionsCard(
            canEdit = state.canEdit,
            canShare = state.canShare,
            isProtected = state.isProtected,
            isSaving = state.isSaving,
            onToggleCanEdit = onToggleCanEdit,
            onToggleCanShare = onToggleCanShare,
        )
        if (state.isProtected) {
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
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
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
                        if (user.isRoot || user.role == "admin") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Column {
                    Text(
                        text =
                            if (user.isRoot) {
                                "Root Administrator"
                            } else {
                                user.role.replaceFirstChar { it.uppercase() }.ifEmpty { "Member" }
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
private fun PermissionsCard(
    canEdit: Boolean,
    canShare: Boolean,
    isProtected: Boolean,
    isSaving: Boolean,
    onToggleCanEdit: () -> Unit,
    onToggleCanShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedGroup(modifier = modifier) {
        // Can Edit — the permission UserPermissionPolicy gates every metadata mutation on.
        // It had no UI at all until #1270, so a member could never be granted edit rights.
        PermissionRow(
            icon = Icons.Outlined.Edit,
            title = stringResource(Res.string.admin_can_edit),
            subtitle = stringResource(Res.string.admin_allow_editing_content_metadata),
            checked = canEdit,
            isProtected = isProtected,
            isSaving = isSaving,
            onToggle = onToggleCanEdit,
        )
        PermissionRow(
            icon = Icons.Outlined.Share,
            title = stringResource(Res.string.admin_can_share),
            subtitle = stringResource(Res.string.admin_allow_sharing_collections_with_other),
            checked = canShare,
            isProtected = isProtected,
            isSaving = isSaving,
            onToggle = onToggleCanShare,
        )
    }
}

/**
 * One permission switch. Both flags present identically, and #1270 added the second — a copied row
 * would be a second place for the protected-user guard and the saving overlay to drift.
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    isProtected: Boolean,
    isSaving: Boolean,
    onToggle: () -> Unit,
) {
    if (isSaving) {
        // While the change is in flight the row shows progress rather than a switch that could be
        // flipped again mid-save.
        SettingRow(icon = icon, title = title, subtitle = subtitle) {
            ListenUpLoadingIndicatorSmall()
        }
    } else {
        SettingToggleRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            checked = checked,
            enabled = !isProtected,
            onCheckedChange = { onToggle() },
        )
    }
}

@Composable
private fun ProtectedUserNotice(modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
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
