package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.client.design.components.ActionTile
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpFab
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.components.RoleChip
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.util.rememberCopyToClipboard
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_inbox
import listenup.composeapp.generated.resources.admin_inbox_subtitle
import listenup.composeapp.generated.resources.admin_inbox_setting_subtitle
import listenup.composeapp.generated.resources.admin_inbox_setting_title
import listenup.composeapp.generated.resources.admin_backup_restore
import listenup.composeapp.generated.resources.admin_confirm_deny_registration
import listenup.composeapp.generated.resources.admin_copy_link
import listenup.composeapp.generated.resources.admin_create_backups_and_restore_server
import listenup.composeapp.generated.resources.admin_deny_registration
import listenup.composeapp.generated.resources.admin_invite_someone
import listenup.composeapp.generated.resources.admin_library_settings
import listenup.composeapp.generated.resources.admin_organize
import listenup.composeapp.generated.resources.admin_organize_subtitle
import listenup.composeapp.generated.resources.admin_library_settings_subtitle
import listenup.composeapp.generated.resources.admin_link_copied
import listenup.composeapp.generated.resources.admin_management
import listenup.composeapp.generated.resources.admin_no_pending_registrations
import listenup.composeapp.generated.resources.admin_registration_approval_desc
import listenup.composeapp.generated.resources.admin_registration_closed_desc
import listenup.composeapp.generated.resources.admin_registration_open_desc
import listenup.composeapp.generated.resources.admin_registration_policy
import listenup.composeapp.generated.resources.admin_registration_policy_approval
import listenup.composeapp.generated.resources.admin_registration_policy_closed
import listenup.composeapp.generated.resources.admin_registration_policy_open
import listenup.composeapp.generated.resources.admin_organize_books_into_collections_for
import listenup.composeapp.generated.resources.admin_pending_invites
import listenup.composeapp.generated.resources.admin_pending_registrations
import listenup.composeapp.generated.resources.admin_push_setting_subtitle
import listenup.composeapp.generated.resources.admin_push_setting_title
import listenup.composeapp.generated.resources.admin_remote_url
import listenup.composeapp.generated.resources.admin_remote_url_placeholder
import listenup.composeapp.generated.resources.admin_revoke_invite
import listenup.composeapp.generated.resources.admin_save_settings
import listenup.composeapp.generated.resources.admin_server_name
import listenup.composeapp.generated.resources.admin_server_settings
import listenup.composeapp.generated.resources.admin_share_your_audiobook_library_with
import listenup.composeapp.generated.resources.admin_they_wont_be_able_to
import listenup.composeapp.generated.resources.admin_view_the_genre_hierarchy_tree
import listenup.composeapp.generated.resources.common_administration
import listenup.composeapp.generated.resources.common_approve
import listenup.composeapp.generated.resources.common_categories
import listenup.composeapp.generated.resources.common_collections
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.common_deny
import listenup.composeapp.generated.resources.common_invite
import listenup.composeapp.generated.resources.common_no_items_found
import listenup.composeapp.generated.resources.common_revoke
import listenup.composeapp.generated.resources.common_users
import listenup.composeapp.generated.resources.connect_listenup_server

/**
 * Combined admin screen showing server settings, users, pending registrations & invites, and the
 * management actions. Material 3 Expressive reskin: a color-blocked hero header, accent-headed
 * [SectionGroup] cards composed of [SettingRow]s, and color-blocked [ActionTile]s for management.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    onBackClick: () -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit = {},
    onCategoriesClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onInboxClick: () -> Unit = {},
    onLibrarySettingsClick: () -> Unit = {},
    onOrganizeClick: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    serverName: String = "",
    onServerNameChange: (String) -> Unit = {},
    remoteUrl: String = "",
    onRemoteUrlChange: (String) -> Unit = {},
    inboxEnabled: Boolean = false,
    onInboxEnabledChange: (Boolean) -> Unit = {},
    pushNotificationsEnabled: Boolean = true,
    onPushNotificationsEnabledChange: (Boolean) -> Unit = {},
    isDirty: Boolean = false,
    onSave: () -> Unit = {},
    settingsError: String? = null,
    onClearSettingsError: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copyToClipboard = rememberCopyToClipboard()
    val linkCopiedMessage = stringResource(Res.string.admin_link_copied)

    val userToDeleteState = remember { mutableStateOf<AdminUserInfo?>(null) }
    val inviteToRevokeState = remember { mutableStateOf<InviteInfo?>(null) }
    val userToDenyState = remember { mutableStateOf<AdminUserInfo?>(null) }

    // Transient mutation-failure error in snackbar (only meaningful in Ready).
    val readyError = (state as? AdminUiState.Ready)?.error
    LaunchedEffect(readyError) {
        readyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(settingsError) {
        settingsError?.let {
            snackbarHostState.showSnackbar(it)
            onClearSettingsError()
        }
    }

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            ColorBlockHero(
                title = stringResource(Res.string.common_administration),
                badgeIcon = Icons.Outlined.Shield,
                onBack = onBackClick,
                overline = serverName,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ListenUpFab(
                onClick = onSave,
                icon = Icons.Outlined.Save,
                contentDescription = stringResource(Res.string.admin_save_settings),
                enabled = isDirty,
            )
        },
    ) { innerPadding ->
        when (val current = state) {
            is AdminUiState.Loading -> {
                FullScreenLoadingIndicator()
            }

            is AdminUiState.Ready -> {
                AdminContent(
                    state = current,
                    onRegistrationPolicyChange = { viewModel.setRegistrationPolicy(it) },
                    onApproveUserClick = { viewModel.approveUser(it.id) },
                    onDenyUserClick = { userToDenyState.value = it },
                    onDeleteUserClick = { userToDeleteState.value = it },
                    onUserClick = onUserClick,
                    onCopyInviteClick = { invite ->
                        copyToClipboard(invite.url)
                        scope.launch {
                            snackbarHostState.showSnackbar(linkCopiedMessage)
                        }
                    },
                    onRevokeInviteClick = { inviteToRevokeState.value = it },
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onBackupClick = onBackupClick,
                    onInboxClick = onInboxClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    onOrganizeClick = onOrganizeClick,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    onRemoteUrlChange = onRemoteUrlChange,
                    inboxEnabled = inboxEnabled,
                    onInboxEnabledChange = onInboxEnabledChange,
                    pushNotificationsEnabled = pushNotificationsEnabled,
                    onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
    }

    AdminConfirmationDialogs(
        viewModel = viewModel,
        userToDeleteState = userToDeleteState,
        inviteToRevokeState = inviteToRevokeState,
        userToDenyState = userToDenyState,
    )
}

@Composable
private fun AdminConfirmationDialogs(
    viewModel: AdminViewModel,
    userToDeleteState: MutableState<AdminUserInfo?>,
    inviteToRevokeState: MutableState<InviteInfo?>,
    userToDenyState: MutableState<AdminUserInfo?>,
) {
    var userToDelete by userToDeleteState
    var inviteToRevoke by inviteToRevokeState
    var userToDeny by userToDenyState

    // Delete user confirmation dialog
    userToDelete?.let { user ->
        ListenUpDestructiveDialog(
            onDismissRequest = { userToDelete = null },
            title = stringResource(Res.string.common_delete_name, "User"),
            text = "Are you sure you want to delete ${user.displayName ?: user.email}? This action cannot be undone.",
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = {
                viewModel.deleteUser(user.id)
                userToDelete = null
            },
            onDismiss = { userToDelete = null },
        )
    }

    // Revoke invite confirmation dialog
    inviteToRevoke?.let { invite ->
        ListenUpDestructiveDialog(
            onDismissRequest = { inviteToRevoke = null },
            title = stringResource(Res.string.admin_revoke_invite),
            text =
                "Are you sure you want to revoke the invite for ${invite.name}? " +
                    stringResource(Res.string.admin_they_wont_be_able_to),
            confirmText = stringResource(Res.string.common_revoke),
            onConfirm = {
                viewModel.revokeInvite(invite.id)
                inviteToRevoke = null
            },
            onDismiss = { inviteToRevoke = null },
        )
    }

    // Deny user confirmation dialog
    userToDeny?.let { user ->
        ListenUpDestructiveDialog(
            onDismissRequest = { userToDeny = null },
            title = stringResource(Res.string.admin_deny_registration),
            text =
                stringResource(Res.string.admin_confirm_deny_registration) +
                    "${user.displayName ?: user.email}? They will need to register again.",
            confirmText = stringResource(Res.string.common_deny),
            onConfirm = {
                viewModel.denyUser(user.id)
                userToDeny = null
            },
            onDismiss = { userToDeny = null },
        )
    }
}

// AdminContent fans hoisted state + per-row callbacks straight into its layout sections;
// a parameter object would only add an indirection layer that Compose tooling discourages.
@Suppress("LongParameterList")
@Composable
private fun AdminContent(
    state: AdminUiState.Ready,
    onRegistrationPolicyChange: (RegistrationPolicy) -> Unit,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
    onUserClick: (String) -> Unit,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onBackupClick: () -> Unit,
    onInboxClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    onOrganizeClick: () -> Unit,
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    inboxEnabled: Boolean,
    onInboxEnabledChange: (Boolean) -> Unit,
    pushNotificationsEnabled: Boolean,
    onPushNotificationsEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isExpanded =
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
        )

    if (isExpanded) {
        AdminTwoPaneContent(
            state = state,
            onRegistrationPolicyChange = onRegistrationPolicyChange,
            onApproveUserClick = onApproveUserClick,
            onDenyUserClick = onDenyUserClick,
            onDeleteUserClick = onDeleteUserClick,
            onUserClick = onUserClick,
            onCopyInviteClick = onCopyInviteClick,
            onRevokeInviteClick = onRevokeInviteClick,
            onInviteClick = onInviteClick,
            onCollectionsClick = onCollectionsClick,
            onCategoriesClick = onCategoriesClick,
            onBackupClick = onBackupClick,
            onInboxClick = onInboxClick,
            onLibrarySettingsClick = onLibrarySettingsClick,
            onOrganizeClick = onOrganizeClick,
            serverName = serverName,
            onServerNameChange = onServerNameChange,
            remoteUrl = remoteUrl,
            onRemoteUrlChange = onRemoteUrlChange,
            inboxEnabled = inboxEnabled,
            onInboxEnabledChange = onInboxEnabledChange,
            pushNotificationsEnabled = pushNotificationsEnabled,
            onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
            modifier = modifier,
        )
    } else {
        LazyColumn(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 24.dp),
        ) {
            item {
                ServerSettingsSection(
                    state = state,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    onRemoteUrlChange = onRemoteUrlChange,
                    inboxEnabled = inboxEnabled,
                    onInboxEnabledChange = onInboxEnabledChange,
                    pushNotificationsEnabled = pushNotificationsEnabled,
                    onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
                    onRegistrationPolicyChange = onRegistrationPolicyChange,
                )
            }

            usersSection(
                state = state,
                onUserClick = onUserClick,
                onDeleteUserClick = onDeleteUserClick,
                onApproveUserClick = onApproveUserClick,
                onDenyUserClick = onDenyUserClick,
                onCopyInviteClick = onCopyInviteClick,
                onRevokeInviteClick = onRevokeInviteClick,
                onInviteClick = onInviteClick,
            )

            item {
                ManagementSection(
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onBackupClick = onBackupClick,
                    onInboxClick = onInboxClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    onOrganizeClick = onOrganizeClick,
                    inboxEnabled = inboxEnabled,
                )
            }
        }
    }
}

// Two-pane expanded layout: Server settings + Users on the left, Management on the right.
@Suppress("LongParameterList")
@Composable
private fun AdminTwoPaneContent(
    state: AdminUiState.Ready,
    onRegistrationPolicyChange: (RegistrationPolicy) -> Unit,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
    onUserClick: (String) -> Unit,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onBackupClick: () -> Unit,
    onInboxClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    onOrganizeClick: () -> Unit,
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    inboxEnabled: Boolean,
    onInboxEnabledChange: (Boolean) -> Unit,
    pushNotificationsEnabled: Boolean,
    onPushNotificationsEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1.1f),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 24.dp),
        ) {
            item {
                ServerSettingsSection(
                    state = state,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    onRemoteUrlChange = onRemoteUrlChange,
                    inboxEnabled = inboxEnabled,
                    onInboxEnabledChange = onInboxEnabledChange,
                    pushNotificationsEnabled = pushNotificationsEnabled,
                    onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
                    onRegistrationPolicyChange = onRegistrationPolicyChange,
                )
            }

            usersSection(
                state = state,
                onUserClick = onUserClick,
                onDeleteUserClick = onDeleteUserClick,
                onApproveUserClick = onApproveUserClick,
                onDenyUserClick = onDenyUserClick,
                onCopyInviteClick = onCopyInviteClick,
                onRevokeInviteClick = onRevokeInviteClick,
                onInviteClick = onInviteClick,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 24.dp),
        ) {
            item {
                ManagementSection(
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onBackupClick = onBackupClick,
                    onInboxClick = onInboxClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    onOrganizeClick = onOrganizeClick,
                    inboxEnabled = inboxEnabled,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Server settings section
// ---------------------------------------------------------------------------

@Composable
private fun ServerSettingsSection(
    state: AdminUiState.Ready,
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    inboxEnabled: Boolean,
    onInboxEnabledChange: (Boolean) -> Unit,
    pushNotificationsEnabled: Boolean,
    onPushNotificationsEnabledChange: (Boolean) -> Unit,
    onRegistrationPolicyChange: (RegistrationPolicy) -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_server_settings),
        icon = Icons.Outlined.Badge,
        accent = MaterialTheme.colorScheme.primary,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ListenUpTextField(
                value = serverName,
                onValueChange = onServerNameChange,
                label = stringResource(Res.string.admin_server_name),
                placeholder = stringResource(Res.string.connect_listenup_server),
                leadingIcon = Icons.Outlined.Badge,
            )
            ListenUpTextField(
                value = remoteUrl,
                onValueChange = onRemoteUrlChange,
                label = stringResource(Res.string.admin_remote_url),
                placeholder = stringResource(Res.string.admin_remote_url_placeholder),
                leadingIcon = Icons.Outlined.CloudDownload,
            )
        }
        RegistrationPolicyControl(
            policy = state.registrationPolicy,
            isToggling = state.isTogglingRegistrationPolicy,
            onChange = onRegistrationPolicyChange,
        )
        SettingRow(
            icon = Icons.Outlined.Inbox,
            title = stringResource(Res.string.admin_inbox_setting_title),
            subtitle = stringResource(Res.string.admin_inbox_setting_subtitle),
            showDivider = true,
        ) {
            Switch(
                checked = inboxEnabled,
                onCheckedChange = onInboxEnabledChange,
            )
        }
        SettingRow(
            icon = Icons.Outlined.Notifications,
            title = stringResource(Res.string.admin_push_setting_title),
            subtitle = stringResource(Res.string.admin_push_setting_subtitle),
            showDivider = true,
        ) {
            Switch(
                checked = pushNotificationsEnabled,
                onCheckedChange = onPushNotificationsEnabledChange,
            )
        }
    }
}

/**
 * Three-state registration control (Open / Approval / Closed) backed by the server's
 * [RegistrationPolicy]. A single segmented selector — not a boolean switch — so all three
 * states are visible and round-trip correctly. The subtitle reflects the *current* policy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegistrationPolicyControl(
    policy: RegistrationPolicy,
    isToggling: Boolean,
    onChange: (RegistrationPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(RegistrationPolicy.OPEN, RegistrationPolicy.APPROVAL_QUEUE, RegistrationPolicy.CLOSED)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.HowToReg,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.admin_registration_policy),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(registrationPolicyDescription(policy)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isToggling) {
                ListenUpLoadingIndicatorSmall()
            }
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = policy == option,
                    onClick = { if (policy != option) onChange(option) },
                    enabled = !isToggling,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(stringResource(registrationPolicyLabel(option)))
                }
            }
        }
    }
}

private fun registrationPolicyLabel(policy: RegistrationPolicy): StringResource =
    when (policy) {
        RegistrationPolicy.OPEN -> Res.string.admin_registration_policy_open
        RegistrationPolicy.APPROVAL_QUEUE -> Res.string.admin_registration_policy_approval
        RegistrationPolicy.CLOSED -> Res.string.admin_registration_policy_closed
    }

private fun registrationPolicyDescription(policy: RegistrationPolicy): StringResource =
    when (policy) {
        RegistrationPolicy.OPEN -> Res.string.admin_registration_open_desc
        RegistrationPolicy.APPROVAL_QUEUE -> Res.string.admin_registration_approval_desc
        RegistrationPolicy.CLOSED -> Res.string.admin_registration_closed_desc
    }

// ---------------------------------------------------------------------------
// Users section (users table + pending registrations + pending invites)
// ---------------------------------------------------------------------------

private fun LazyListScope.usersSection(
    state: AdminUiState.Ready,
    onUserClick: (String) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
    onInviteClick: () -> Unit,
) {
    item {
        UsersGroup(
            state = state,
            onUserClick = onUserClick,
            onDeleteUserClick = onDeleteUserClick,
            onInviteClick = onInviteClick,
        )
    }

    if (state.registrationPolicy == RegistrationPolicy.APPROVAL_QUEUE) {
        item {
            PendingRegistrationsGroup(
                state = state,
                onApproveUserClick = onApproveUserClick,
                onDenyUserClick = onDenyUserClick,
            )
        }
    }

    if (state.pendingInvites.isNotEmpty()) {
        item {
            PendingInvitesGroup(
                state = state,
                onCopyInviteClick = onCopyInviteClick,
                onRevokeInviteClick = onRevokeInviteClick,
            )
        }
    }
}

@Composable
private fun UsersGroup(
    state: AdminUiState.Ready,
    onUserClick: (String) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
    onInviteClick: () -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.common_users),
        icon = Icons.Outlined.Group,
        accent = MaterialTheme.colorScheme.primary,
        trailing = {
            ScallopBadge(
                size = 26.dp,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = state.users.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            PillChip(
                label = stringResource(Res.string.common_invite),
                onClick = onInviteClick,
                leadingIcon = Icons.Outlined.PersonAdd,
            )
        },
    ) {
        if (state.users.isEmpty()) {
            Text(
                text = stringResource(Res.string.common_no_items_found, "users"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            state.users.forEachIndexed { index, user ->
                UserRow(
                    user = user,
                    isDeleting = state.deletingUserId == user.id,
                    showDivider = index > 0,
                    onClick = { onUserClick(user.id) },
                    onDeleteClick = { onDeleteUserClick(user) },
                )
            }
        }
    }
}

@Composable
private fun UserRow(
    user: AdminUserInfo,
    isDeleting: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val isRoot = user.isRoot || user.role == "admin"
    val roleLabel =
        if (user.isRoot) {
            "Root"
        } else {
            user.role.replaceFirstChar { it.uppercase() }.ifEmpty { "Member" }
        }
    SettingRow(
        title = user.displayName ?: user.email,
        subtitle = user.email,
        showDivider = showDivider,
        onClick = onClick,
        leading = { UserAvatar(userId = user.id, size = AvatarSize.Medium) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoleChip(label = roleLabel, isRoot = isRoot)
            if (!user.isProtected) {
                if (isDeleting) {
                    ListenUpLoadingIndicatorSmall()
                } else {
                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(Res.string.common_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingRegistrationsGroup(
    state: AdminUiState.Ready,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    SectionGroup(
        label = stringResource(Res.string.admin_pending_registrations),
        icon = Icons.Outlined.HowToReg,
        accent = accent,
    ) {
        if (state.pendingUsers.isEmpty()) {
            Text(
                text = stringResource(Res.string.admin_no_pending_registrations),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            state.pendingUsers.forEachIndexed { index, user ->
                PendingUserRow(
                    user = user,
                    isApproving = state.approvingUserId == user.id,
                    isDenying = state.denyingUserId == user.id,
                    showDivider = index > 0,
                    onApproveClick = { onApproveUserClick(user) },
                    onDenyClick = { onDenyUserClick(user) },
                )
            }
        }
    }
}

@Composable
private fun PendingUserRow(
    user: AdminUserInfo,
    isApproving: Boolean,
    isDenying: Boolean,
    showDivider: Boolean,
    onApproveClick: () -> Unit,
    onDenyClick: () -> Unit,
) {
    val name =
        user.displayName
            ?: "${user.firstName ?: ""} ${user.lastName ?: ""}".trim().ifEmpty { user.email }
    SettingRow(
        title = name,
        subtitle = user.email,
        showDivider = showDivider,
        // A pending registrant has no server-side public profile yet, so drive initials from their
        // name rather than the generic add-person glyph / an indefinite loading circle.
        leading = { UserAvatar(userId = user.id, size = AvatarSize.Medium, fallbackName = name) },
    ) {
        if (isApproving || isDenying) {
            ListenUpLoadingIndicatorSmall()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDenyClick) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(Res.string.common_deny))
                }
                FilledTonalButton(onClick = onApproveClick) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(Res.string.common_approve))
                }
            }
        }
    }
}

@Composable
private fun PendingInvitesGroup(
    state: AdminUiState.Ready,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.secondary
    SectionGroup(
        label = stringResource(Res.string.admin_pending_invites),
        icon = Icons.Outlined.PersonAdd,
        accent = accent,
    ) {
        state.pendingInvites.forEachIndexed { index, invite ->
            InviteRow(
                invite = invite,
                accent = accent,
                isRevoking = state.revokingInviteId == invite.id,
                showDivider = index > 0,
                onCopyClick = { onCopyInviteClick(invite) },
                onRevokeClick = { onRevokeInviteClick(invite) },
            )
        }
    }
}

@Composable
private fun InviteRow(
    invite: InviteInfo,
    accent: Color,
    isRevoking: Boolean,
    showDivider: Boolean,
    onCopyClick: () -> Unit,
    onRevokeClick: () -> Unit,
) {
    SettingRow(
        icon = Icons.Outlined.PersonAdd,
        accent = accent,
        title = invite.name,
        subtitle = invite.email,
        showDivider = showDivider,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoleChip(label = invite.role.replaceFirstChar { it.uppercase() })
            IconButton(onClick = onCopyClick) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(Res.string.admin_copy_link),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isRevoking) {
                ListenUpLoadingIndicatorSmall()
            } else {
                IconButton(onClick = onRevokeClick) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(Res.string.common_revoke),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Management section
// ---------------------------------------------------------------------------

@Composable
internal fun ManagementSection(
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onBackupClick: () -> Unit,
    onInboxClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    onOrganizeClick: () -> Unit,
    inboxEnabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(Res.string.admin_management),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        ActionTile(
            title = stringResource(Res.string.admin_library_settings),
            subtitle = stringResource(Res.string.admin_library_settings_subtitle),
            icon = Icons.Outlined.FolderOpen,
            onClick = onLibrarySettingsClick,
            containerColor = colors.secondaryContainer,
            badgeColor = colors.secondary,
            badgeContentColor = colors.onSecondary,
        )
        ActionTile(
            title = stringResource(Res.string.admin_organize),
            subtitle = stringResource(Res.string.admin_organize_subtitle),
            icon = Icons.Outlined.DriveFileMove,
            onClick = onOrganizeClick,
            containerColor = colors.secondaryContainer,
            badgeColor = colors.secondary,
            badgeContentColor = colors.onSecondary,
        )
        if (inboxEnabled) {
            ActionTile(
                title = stringResource(Res.string.admin_inbox),
                subtitle = stringResource(Res.string.admin_inbox_subtitle),
                icon = Icons.Outlined.Inbox,
                onClick = onInboxClick,
                containerColor = colors.tertiaryContainer,
                badgeColor = colors.tertiary,
                badgeContentColor = colors.onTertiary,
            )
        }
        ActionTile(
            title = stringResource(Res.string.admin_invite_someone),
            subtitle = stringResource(Res.string.admin_share_your_audiobook_library_with),
            icon = Icons.Outlined.PersonAdd,
            onClick = onInviteClick,
            containerColor = colors.primaryContainer,
            badgeColor = colors.primary,
            badgeContentColor = colors.onPrimary,
        )
        ActionTile(
            title = stringResource(Res.string.common_collections),
            subtitle = stringResource(Res.string.admin_organize_books_into_collections_for),
            icon = Icons.Outlined.Folder,
            onClick = onCollectionsClick,
            containerColor = colors.tertiaryContainer,
            badgeColor = colors.tertiary,
            badgeContentColor = colors.onTertiary,
        )
        ActionTile(
            title = stringResource(Res.string.common_categories),
            subtitle = stringResource(Res.string.admin_view_the_genre_hierarchy_tree),
            icon = Icons.Outlined.Category,
            onClick = onCategoriesClick,
            containerColor = colors.secondaryContainer,
            badgeColor = colors.secondary,
            badgeContentColor = colors.onSecondary,
        )
        ActionTile(
            title = stringResource(Res.string.admin_backup_restore),
            subtitle = stringResource(Res.string.admin_create_backups_and_restore_server),
            icon = Icons.Outlined.Backup,
            onClick = onBackupClick,
            containerColor = colors.surfaceContainerHigh,
            badgeColor = colors.tertiary,
            badgeContentColor = colors.onTertiary,
        )
    }
}
