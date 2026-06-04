
package com.calypsan.listenup.client.features.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Save
import com.calypsan.listenup.client.design.components.ListenUpFab
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CardDefaults
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.calypsan.listenup.client.design.util.rememberCopyToClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_admin
import listenup.composeapp.generated.resources.admin_allow_anyone_to_request_an
import listenup.composeapp.generated.resources.common_approve
import listenup.composeapp.generated.resources.admin_confirm_deny_registration
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.admin_backup_restore
import listenup.composeapp.generated.resources.common_categories
import listenup.composeapp.generated.resources.common_collections
import listenup.composeapp.generated.resources.admin_copy_link
import listenup.composeapp.generated.resources.admin_create_backups_and_restore_server
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.common_deny
import listenup.composeapp.generated.resources.admin_deny_registration
import listenup.composeapp.generated.resources.common_email
import listenup.composeapp.generated.resources.common_inbox
import listenup.composeapp.generated.resources.admin_inbox_workflow
import listenup.composeapp.generated.resources.admin_invite_someone
import listenup.composeapp.generated.resources.connect_listenup_server
import listenup.composeapp.generated.resources.common_name
import listenup.composeapp.generated.resources.admin_no_pending_registrations
import listenup.composeapp.generated.resources.common_no_items_found
import listenup.composeapp.generated.resources.admin_open_registration
import listenup.composeapp.generated.resources.admin_organize_books_into_collections_for
import listenup.composeapp.generated.resources.admin_pending_invites
import listenup.composeapp.generated.resources.admin_pending_registrations
import listenup.composeapp.generated.resources.common_permissions
import listenup.composeapp.generated.resources.admin_remote_url
import listenup.composeapp.generated.resources.admin_review_new_books_before_they
import listenup.composeapp.generated.resources.common_revoke
import listenup.composeapp.generated.resources.admin_revoke_invite
import listenup.composeapp.generated.resources.common_role
import listenup.composeapp.generated.resources.admin_save_settings
import listenup.composeapp.generated.resources.admin_server_name
import listenup.composeapp.generated.resources.admin_share_your_audiobook_library_with
import listenup.composeapp.generated.resources.admin_they_wont_be_able_to
import listenup.composeapp.generated.resources.common_users
import listenup.composeapp.generated.resources.admin_view_the_genre_hierarchy_tree
import listenup.composeapp.generated.resources.admin_unmapped_genres
import listenup.composeapp.generated.resources.admin_map_scanned_genre_strings_to_your_taxonomy
import listenup.composeapp.generated.resources.common_administration
import listenup.composeapp.generated.resources.common_settings

/**
 * Combined admin screen showing users, pending invites, and invite action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    onBackClick: () -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit = {},
    onCategoriesClick: () -> Unit = {},
    onUnmappedGenresClick: () -> Unit = {},
    onInboxClick: () -> Unit = {},
    onBackupClick: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    serverName: String = "",
    onServerNameChange: (String) -> Unit = {},
    remoteUrl: String = "",
    onRemoteUrlChange: (String) -> Unit = {},
    inboxEnabled: Boolean = false,
    inboxCount: Int = 0,
    isSaving: Boolean = false,
    onInboxEnabledChange: (Boolean) -> Unit = {},
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.common_administration)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.common_back))
                    }
                },
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

            is AdminUiState.Error -> {
                Text(
                    text = current.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(innerPadding).padding(16.dp),
                )
            }

            is AdminUiState.Ready -> {
                AdminContent(
                    state = current,
                    onOpenRegistrationChange = { viewModel.setOpenRegistration(it) },
                    onApproveUserClick = { viewModel.approveUser(it.id) },
                    onDenyUserClick = { userToDenyState.value = it },
                    onDeleteUserClick = { userToDeleteState.value = it },
                    onUserClick = onUserClick,
                    onCopyInviteClick = { invite ->
                        copyToClipboard(invite.url)
                        scope.launch {
                            snackbarHostState.showSnackbar("Link copied!")
                        }
                    },
                    onRevokeInviteClick = { inviteToRevokeState.value = it },
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onUnmappedGenresClick = onUnmappedGenresClick,
                    onInboxClick = onInboxClick,
                    onBackupClick = onBackupClick,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    onRemoteUrlChange = onRemoteUrlChange,
                    inboxEnabled = inboxEnabled,
                    inboxCount = inboxCount,
                    isSaving = isSaving,
                    onInboxEnabledChange = onInboxEnabledChange,
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

// AdminContent fans hoisted state + per-row callbacks straight into its LazyColumn sections;
// a parameter object would only add an indirection layer that Compose tooling discourages.
@Suppress("LongParameterList")
@Composable
private fun AdminContent(
    state: AdminUiState.Ready,
    onOpenRegistrationChange: (Boolean) -> Unit,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
    onUserClick: (String) -> Unit,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onUnmappedGenresClick: () -> Unit,
    onInboxClick: () -> Unit,
    onBackupClick: () -> Unit,
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    inboxEnabled: Boolean,
    inboxCount: Int,
    isSaving: Boolean,
    onInboxEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
    ) {
        // Settings section
        item {
            Text(
                text = stringResource(Res.string.common_settings),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
        }

        item {
            SettingsCard(
                serverName = serverName,
                onServerNameChange = onServerNameChange,
                remoteUrl = remoteUrl,
                onRemoteUrlChange = onRemoteUrlChange,
                openRegistration = state.openRegistration,
                isTogglingOpenRegistration = state.isTogglingOpenRegistration,
                onOpenRegistrationChange = onOpenRegistrationChange,
                inboxEnabled = inboxEnabled,
                isSaving = isSaving,
                onInboxEnabledChange = onInboxEnabledChange,
            )
        }

        // Pending users section (only shown when open registration is enabled)
        if (state.openRegistration) {
            pendingUsersSection(
                state = state,
                onApproveUserClick = onApproveUserClick,
                onDenyUserClick = onDenyUserClick,
            )
        }

        usersTableSection(
            state = state,
            onUserClick = onUserClick,
            onDeleteUserClick = onDeleteUserClick,
        )

        // Pending invites section (only if there are any)
        if (state.pendingInvites.isNotEmpty()) {
            pendingInvitesSection(
                state = state,
                onCopyInviteClick = onCopyInviteClick,
                onRevokeInviteClick = onRevokeInviteClick,
            )
        }

        // Invite someone button
        item {
            Spacer(modifier = Modifier.height(24.dp))
            InviteSomeoneCard(onClick = onInviteClick)
        }

        // Collections button
        item {
            Spacer(modifier = Modifier.height(12.dp))
            CollectionsCard(onClick = onCollectionsClick)
        }

        // Categories button
        item {
            Spacer(modifier = Modifier.height(12.dp))
            CategoriesCard(onClick = onCategoriesClick)
        }

        // Unmapped Genres button
        item {
            Spacer(modifier = Modifier.height(12.dp))
            UnmappedGenresCard(onClick = onUnmappedGenresClick)
        }

        // Inbox button (only shown when inbox workflow is enabled)
        if (inboxEnabled) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                InboxCard(
                    inboxCount = inboxCount,
                    onClick = onInboxClick,
                )
            }
        }

        // Backup & Restore button
        item {
            Spacer(modifier = Modifier.height(12.dp))
            BackupCard(onClick = onBackupClick)
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun LazyListScope.pendingUsersSection(
    state: AdminUiState.Ready,
    onApproveUserClick: (AdminUserInfo) -> Unit,
    onDenyUserClick: (AdminUserInfo) -> Unit,
) {
    item {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(Res.string.admin_pending_registrations),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    item {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors =
                CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
        ) {
            if (state.pendingUsers.isEmpty()) {
                Text(
                    text = stringResource(Res.string.admin_no_pending_registrations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Column {
                    state.pendingUsers.forEachIndexed { index, user ->
                        PendingUserRow(
                            user = user,
                            isApproving = state.approvingUserId == user.id,
                            isDenying = state.denyingUserId == user.id,
                            onApproveClick = { onApproveUserClick(user) },
                            onDenyClick = { onDenyUserClick(user) },
                        )
                        if (index < state.pendingUsers.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.usersTableSection(
    state: AdminUiState.Ready,
    onUserClick: (String) -> Unit,
    onDeleteUserClick: (AdminUserInfo) -> Unit,
) {
    // Users section header
    item {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(Res.string.common_users),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    // Users table
    item {
        val cardShape = MaterialTheme.shapes.large
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors =
                CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
        ) {
            Column {
                // Table header - clip top corners to match card shape
                UserTableHeader(
                    modifier =
                        Modifier.clip(
                            RoundedCornerShape(
                                topStart = 28.dp,
                                topEnd = 28.dp,
                            ),
                        ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // User rows
                if (state.users.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.common_no_items_found, "users"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    state.users.forEachIndexed { index, user ->
                        UserTableRow(
                            user = user,
                            isDeleting = state.deletingUserId == user.id,
                            onClick = { onUserClick(user.id) },
                            onDeleteClick = { onDeleteUserClick(user) },
                        )
                        if (index < state.users.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.pendingInvitesSection(
    state: AdminUiState.Ready,
    onCopyInviteClick: (InviteInfo) -> Unit,
    onRevokeInviteClick: (InviteInfo) -> Unit,
) {
    item {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(Res.string.admin_pending_invites),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }

    item {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors =
                CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
        ) {
            Column {
                state.pendingInvites.forEachIndexed { index, invite ->
                    InviteRow(
                        invite = invite,
                        isRevoking = state.revokingInviteId == invite.id,
                        onCopyClick = { onCopyInviteClick(invite) },
                        onRevokeClick = { onRevokeInviteClick(invite) },
                    )
                    if (index < state.pendingInvites.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    openRegistration: Boolean,
    isTogglingOpenRegistration: Boolean,
    onOpenRegistrationChange: (Boolean) -> Unit,
    inboxEnabled: Boolean,
    isSaving: Boolean,
    onInboxEnabledChange: (Boolean) -> Unit,
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
        Column {
            SettingsServerNameRow(
                serverName = serverName,
                onServerNameChange = onServerNameChange,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            SettingsRemoteUrlRow(
                remoteUrl = remoteUrl,
                onRemoteUrlChange = onRemoteUrlChange,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            SettingsOpenRegistrationRow(
                openRegistration = openRegistration,
                isTogglingOpenRegistration = isTogglingOpenRegistration,
                onOpenRegistrationChange = onOpenRegistrationChange,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            SettingsInboxWorkflowRow(
                inboxEnabled = inboxEnabled,
                isSaving = isSaving,
                onInboxEnabledChange = onInboxEnabledChange,
            )
        }
    }
}

@Composable
private fun SettingsServerNameRow(
    serverName: String,
    onServerNameChange: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Badge,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = serverName,
            onValueChange = onServerNameChange,
            label = { Text(stringResource(Res.string.admin_server_name)) },
            placeholder = { Text(stringResource(Res.string.connect_listenup_server)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SettingsRemoteUrlRow(
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = remoteUrl,
            onValueChange = onRemoteUrlChange,
            label = { Text(stringResource(Res.string.admin_remote_url)) },
            placeholder = { Text("https://audiobooks.example.com") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SettingsOpenRegistrationRow(
    openRegistration: Boolean,
    isTogglingOpenRegistration: Boolean,
    onOpenRegistrationChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.HowToReg,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.admin_open_registration),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.admin_allow_anyone_to_request_an),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isTogglingOpenRegistration) {
            ListenUpLoadingIndicatorSmall()
        } else {
            Switch(
                checked = openRegistration,
                onCheckedChange = onOpenRegistrationChange,
            )
        }
    }
}

@Composable
private fun SettingsInboxWorkflowRow(
    inboxEnabled: Boolean,
    isSaving: Boolean,
    onInboxEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Inbox,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.admin_inbox_workflow),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.admin_review_new_books_before_they),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isSaving) {
            ListenUpLoadingIndicatorSmall()
        } else {
            Switch(
                checked = inboxEnabled,
                onCheckedChange = onInboxEnabledChange,
            )
        }
    }
}

@Composable
private fun PendingUserRow(
    user: AdminUserInfo,
    isApproving: Boolean,
    isDenying: Boolean,
    onApproveClick: () -> Unit,
    onDenyClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Name and email
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    user.displayName ?: "${user.firstName ?: ""} ${user.lastName ?: ""}".trim().ifEmpty { user.email },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = user.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Action buttons
        if (isApproving || isDenying) {
            ListenUpLoadingIndicatorSmall()
        } else {
            OutlinedButton(
                onClick = onDenyClick,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(Res.string.common_deny))
            }
            FilledTonalButton(
                onClick = onApproveClick,
            ) {
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

@Composable
private fun UserTableHeader(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.common_name),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.common_email),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(Res.string.common_role),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = stringResource(Res.string.common_permissions),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        // Space for delete button
        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun UserTableRow(
    user: AdminUserInfo,
    isDeleting: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name with admin indicator
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = user.displayName ?: user.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (user.isRoot || user.role == "admin") {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = stringResource(Res.string.common_admin),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Email
        Text(
            text = user.email,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // Role
        Text(
            text = if (user.isRoot) "Root" else user.role.replaceFirstChar { it.uppercase() }.ifEmpty { "Member" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(0.4f),
        )

        // Permissions indicators
        Row(
            modifier = Modifier.weight(0.4f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = if (user.permissions.canShare) "Can share" else "Cannot share",
                tint =
                    if (user.permissions.canShare) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                modifier = Modifier.size(16.dp),
            )
        }

        // Delete button
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
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun InviteRow(
    invite: InviteInfo,
    isRevoking: Boolean,
    onCopyClick: () -> Unit,
    onRevokeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Name and email
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = invite.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = invite.email,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Role badge
        Text(
            text = invite.role.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
        )

        // Copy link button
        IconButton(onClick = onCopyClick) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(Res.string.admin_copy_link),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        // Revoke button
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

@Composable
private fun InviteSomeoneCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.PersonAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.admin_invite_someone),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(Res.string.admin_share_your_audiobook_library_with),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun CollectionsCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.common_collections),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = stringResource(Res.string.admin_organize_books_into_collections_for),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun CategoriesCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Category,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.common_categories),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.admin_view_the_genre_hierarchy_tree),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UnmappedGenresCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Rule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.admin_unmapped_genres),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.admin_map_scanned_genre_strings_to_your_taxonomy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InboxCard(
    inboxCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
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
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.common_inbox),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text =
                        if (inboxCount > 0) {
                            "$inboxCount book${if (inboxCount != 1) "s" else ""} awaiting review"
                        } else {
                            "Review newly scanned books before publishing"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun BackupCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Backup,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.admin_backup_restore),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.admin_create_backups_and_restore_server),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
