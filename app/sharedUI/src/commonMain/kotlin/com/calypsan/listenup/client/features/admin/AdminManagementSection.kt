package com.calypsan.listenup.client.features.admin

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.client.design.components.ActionTile
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_deny_reset
import listenup.composeapp.generated.resources.admin_inbox
import listenup.composeapp.generated.resources.admin_inbox_subtitle
import listenup.composeapp.generated.resources.admin_backup_restore
import listenup.composeapp.generated.resources.import_entry_subtitle
import listenup.composeapp.generated.resources.import_title
import listenup.composeapp.generated.resources.admin_confirm_deny_registration
import listenup.composeapp.generated.resources.admin_create_backups_and_restore_server
import listenup.composeapp.generated.resources.admin_deny_registration
import listenup.composeapp.generated.resources.admin_invite_someone
import listenup.composeapp.generated.resources.admin_library_settings
import listenup.composeapp.generated.resources.admin_organize
import listenup.composeapp.generated.resources.admin_organize_subtitle
import listenup.composeapp.generated.resources.admin_upload_books
import listenup.composeapp.generated.resources.admin_upload_books_subtitle
import listenup.composeapp.generated.resources.admin_library_settings_subtitle
import listenup.composeapp.generated.resources.admin_management
import listenup.composeapp.generated.resources.admin_organize_books_into_collections_for
import listenup.composeapp.generated.resources.admin_revoke_invite
import listenup.composeapp.generated.resources.admin_share_your_audiobook_library_with
import listenup.composeapp.generated.resources.admin_they_wont_be_able_to
import listenup.composeapp.generated.resources.admin_view_the_genre_hierarchy_tree
import listenup.composeapp.generated.resources.common_categories
import listenup.composeapp.generated.resources.common_collections
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.common_deny
import listenup.composeapp.generated.resources.common_revoke
import listenup.composeapp.generated.resources.admin_confirm_delete_user
import listenup.composeapp.generated.resources.admin_confirm_deny_reset

@Composable
internal fun AdminConfirmationDialogs(
    viewModel: AdminViewModel,
    userToDeleteState: MutableState<AdminUserInfo?>,
    inviteToRevokeState: MutableState<InviteInfo?>,
    userToDenyState: MutableState<AdminUserInfo?>,
    resetToDenyState: MutableState<PasswordResetRequest?>,
) {
    var userToDelete by userToDeleteState
    var inviteToRevoke by inviteToRevokeState
    var userToDeny by userToDenyState
    var resetToDeny by resetToDenyState

    // Delete user confirmation dialog
    userToDelete?.let { user ->
        ListenUpDestructiveDialog(
            onDismissRequest = { userToDelete = null },
            title = stringResource(Res.string.common_delete_name, "User"),
            text = stringResource(Res.string.admin_confirm_delete_user, user.displayName ?: user.email),
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

    // Deny password-reset confirmation dialog
    resetToDeny?.let { request ->
        ListenUpDestructiveDialog(
            onDismissRequest = { resetToDeny = null },
            title = stringResource(Res.string.admin_deny_reset),
            text = stringResource(Res.string.admin_confirm_deny_reset, request.displayName),
            confirmText = stringResource(Res.string.common_deny),
            onConfirm = {
                viewModel.decidePasswordReset(request.id, approved = false)
                resetToDeny = null
            },
            onDismiss = { resetToDeny = null },
        )
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
    onImportClick: () -> Unit,
    onUploadBooksClick: () -> Unit,
    onInboxClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    onOrganizeClick: () -> Unit,
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
            icon = ListenUpIcons.FolderOpen,
            onClick = onLibrarySettingsClick,
            containerColor = colors.secondaryContainer,
            badgeColor = colors.secondary,
            badgeContentColor = colors.onSecondary,
        )
        ActionTile(
            title = stringResource(Res.string.admin_upload_books),
            subtitle = stringResource(Res.string.admin_upload_books_subtitle),
            icon = ListenUpIcons.CloudUpload,
            onClick = onUploadBooksClick,
            containerColor = colors.primaryContainer,
            badgeColor = colors.primary,
            badgeContentColor = colors.onPrimary,
        )
        ActionTile(
            title = stringResource(Res.string.admin_organize),
            subtitle = stringResource(Res.string.admin_organize_subtitle),
            icon = ListenUpIcons.DriveFileMove,
            onClick = onOrganizeClick,
            containerColor = colors.secondaryContainer,
            badgeColor = colors.secondary,
            badgeContentColor = colors.onSecondary,
        )
        // Unconditional. The hold-for-review setting governs whether *healthy* books wait here;
        // a book the scanner could not understand lands here regardless, so the way in must not
        // depend on a setting the admin may never have turned on.
        ActionTile(
            title = stringResource(Res.string.admin_inbox),
            subtitle = stringResource(Res.string.admin_inbox_subtitle),
            icon = ListenUpIcons.Inbox,
            onClick = onInboxClick,
            containerColor = colors.tertiaryContainer,
            badgeColor = colors.tertiary,
            badgeContentColor = colors.onTertiary,
        )
        ActionTile(
            title = stringResource(Res.string.admin_invite_someone),
            subtitle = stringResource(Res.string.admin_share_your_audiobook_library_with),
            icon = ListenUpIcons.PersonAdd,
            onClick = onInviteClick,
            containerColor = colors.primaryContainer,
            badgeColor = colors.primary,
            badgeContentColor = colors.onPrimary,
        )
        ActionTile(
            title = stringResource(Res.string.common_collections),
            subtitle = stringResource(Res.string.admin_organize_books_into_collections_for),
            icon = ListenUpIcons.Folder,
            onClick = onCollectionsClick,
            containerColor = colors.tertiaryContainer,
            badgeColor = colors.tertiary,
            badgeContentColor = colors.onTertiary,
        )
        ActionTile(
            title = stringResource(Res.string.common_categories),
            subtitle = stringResource(Res.string.admin_view_the_genre_hierarchy_tree),
            icon = ListenUpIcons.Category,
            onClick = onCategoriesClick,
            containerColor = colors.secondaryContainer,
            badgeColor = colors.secondary,
            badgeContentColor = colors.onSecondary,
        )
        ActionTile(
            title = stringResource(Res.string.admin_backup_restore),
            subtitle = stringResource(Res.string.admin_create_backups_and_restore_server),
            icon = ListenUpIcons.Backup,
            onClick = onBackupClick,
            containerColor = colors.surfaceContainerHigh,
            badgeColor = colors.tertiary,
            badgeContentColor = colors.onTertiary,
        )
        ActionTile(
            title = stringResource(Res.string.import_title),
            subtitle = stringResource(Res.string.import_entry_subtitle),
            icon = ListenUpIcons.CloudDownload,
            onClick = onImportClick,
            containerColor = colors.surfaceContainerHigh,
            badgeColor = colors.tertiary,
            badgeContentColor = colors.onTertiary,
        )
    }
}
