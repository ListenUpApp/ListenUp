package com.calypsan.listenup.client.features.admin

import com.calypsan.listenup.client.design.components.ListenUpAlertDialog
import com.calypsan.listenup.client.design.components.SettingToggleRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceUnavailable
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.design.components.ActionTile
import com.calypsan.listenup.client.design.components.AvatarSize
import com.calypsan.listenup.client.design.components.ColorBlockHero
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.SaveAction
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpTextField
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.PillChip
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.components.RoleChip
import com.calypsan.listenup.client.design.components.ScallopBadge
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.SettingRow
import com.calypsan.listenup.client.design.components.UserAvatar
import com.calypsan.listenup.client.design.components.listenUpOutlinedBorder
import com.calypsan.listenup.client.design.util.ratingSourceLabel
import com.calypsan.listenup.client.design.util.isJustNow
import com.calypsan.listenup.client.design.util.relativeTime
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.client.design.util.rememberCopyToClipboard
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.client.presentation.admin.HardcoverTokenSave
import com.calypsan.listenup.client.presentation.admin.AdminViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.admin_approve_reset
import listenup.composeapp.generated.resources.admin_deny_reset
import listenup.composeapp.generated.resources.admin_inbox
import listenup.composeapp.generated.resources.admin_inbox_subtitle
import listenup.composeapp.generated.resources.admin_inbox_setting_subtitle
import listenup.composeapp.generated.resources.admin_inbox_setting_title
import listenup.composeapp.generated.resources.admin_backup_restore
import listenup.composeapp.generated.resources.import_entry_subtitle
import listenup.composeapp.generated.resources.import_title
import listenup.composeapp.generated.resources.admin_confirm_deny_registration
import listenup.composeapp.generated.resources.admin_copy_link
import listenup.composeapp.generated.resources.admin_create_backups_and_restore_server
import listenup.composeapp.generated.resources.admin_deny_registration
import listenup.composeapp.generated.resources.admin_invite_someone
import listenup.composeapp.generated.resources.admin_library_settings
import listenup.composeapp.generated.resources.admin_organize
import listenup.composeapp.generated.resources.admin_organize_subtitle
import listenup.composeapp.generated.resources.admin_upload_books
import listenup.composeapp.generated.resources.admin_upload_books_subtitle
import listenup.composeapp.generated.resources.admin_library_settings_subtitle
import listenup.composeapp.generated.resources.admin_link_copied
import listenup.composeapp.generated.resources.admin_management
import listenup.composeapp.generated.resources.admin_no_pending_password_resets
import listenup.composeapp.generated.resources.admin_no_pending_registrations
import listenup.composeapp.generated.resources.admin_password_resets
import listenup.composeapp.generated.resources.admin_registration_approval_desc
import listenup.composeapp.generated.resources.admin_reset_code_recipient_fallback
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
import listenup.composeapp.generated.resources.admin_rating_source_error
import listenup.composeapp.generated.resources.admin_rating_source_last_fetched
import listenup.composeapp.generated.resources.admin_rating_source_last_fetched_just_now
import listenup.composeapp.generated.resources.admin_rating_source_never_fetched
import listenup.composeapp.generated.resources.admin_rating_source_no_connection
import listenup.composeapp.generated.resources.admin_rating_source_not_configured
import listenup.composeapp.generated.resources.admin_rating_source_paused
import listenup.composeapp.generated.resources.admin_rating_source_paused_plain
import listenup.composeapp.generated.resources.admin_rating_source_unavailable
import listenup.composeapp.generated.resources.admin_rating_source_using_connection
import listenup.composeapp.generated.resources.admin_rating_sources_hint
import listenup.composeapp.generated.resources.admin_rating_sources_title
import listenup.composeapp.generated.resources.admin_remote_url
import listenup.composeapp.generated.resources.admin_remote_url_placeholder
import listenup.composeapp.generated.resources.admin_reset_code_copied
import listenup.composeapp.generated.resources.admin_reset_code_done
import listenup.composeapp.generated.resources.admin_reset_code_instruction
import listenup.composeapp.generated.resources.admin_reset_code_title
import listenup.composeapp.generated.resources.admin_revoke_invite
import listenup.composeapp.generated.resources.admin_server_name
import listenup.composeapp.generated.resources.admin_server_settings
import listenup.composeapp.generated.resources.admin_share_your_audiobook_library_with
import listenup.composeapp.generated.resources.admin_they_wont_be_able_to
import listenup.composeapp.generated.resources.admin_view_the_genre_hierarchy_tree
import listenup.composeapp.generated.resources.common_administration
import listenup.composeapp.generated.resources.common_approve
import listenup.composeapp.generated.resources.common_categories
import listenup.composeapp.generated.resources.common_collections
import listenup.composeapp.generated.resources.common_copy
import listenup.composeapp.generated.resources.common_delete
import listenup.composeapp.generated.resources.common_delete_name
import listenup.composeapp.generated.resources.common_deny
import listenup.composeapp.generated.resources.common_invite
import listenup.composeapp.generated.resources.common_no_items_found
import listenup.composeapp.generated.resources.common_revoke
import listenup.composeapp.generated.resources.common_users
import listenup.composeapp.generated.resources.connect_listenup_server
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.calypsan.listenup.client.design.components.rememberHeroScrollBehavior

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
    onImportClick: () -> Unit = {},
    onUploadBooksClick: () -> Unit = {},
    onInboxClick: () -> Unit = {},
    onLibrarySettingsClick: () -> Unit = {},
    onOrganizeClick: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    serverName: String = "",
    onServerNameChange: (String) -> Unit = {},
    remoteUrl: String = "",
    onRemoteUrlChange: (String) -> Unit = {},
    holdNewBooksForReview: Boolean = false,
    onHoldNewBooksForReviewChange: (Boolean) -> Unit = {},
    pushNotificationsEnabled: Boolean = true,
    onPushNotificationsEnabledChange: (Boolean) -> Unit = {},
    ratingSources: List<RatingSourceStatus> = emptyList(),
    onRatingSourceEnabledChange: (ExternalRatingSource, Boolean) -> Unit = { _, _ -> },
    hardcoverSource: HardcoverSourceStatus? = null,
    hardcoverTokenSave: HardcoverTokenSave = HardcoverTokenSave.Idle,
    hardcoverActions: HardcoverSourceActions = HardcoverSourceActions(),
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
    val resetCodeCopiedMessage = stringResource(Res.string.admin_reset_code_copied)

    val userToDeleteState = remember { mutableStateOf<AdminUserInfo?>(null) }
    val inviteToRevokeState = remember { mutableStateOf<InviteInfo?>(null) }
    val userToDenyState = remember { mutableStateOf<AdminUserInfo?>(null) }
    val resetToDenyState = remember { mutableStateOf<PasswordResetRequest?>(null) }

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

    // At a large font the hero slides away as the settings scroll, rather than holding a third of the screen.
    val heroScroll = rememberHeroScrollBehavior()
    ListenUpScaffold(
        modifier = modifier.then(heroScroll?.let { Modifier.nestedScroll(it.nestedScrollConnection) } ?: Modifier),
        topBar = {
            ColorBlockHero(
                title = stringResource(Res.string.common_administration),
                badgeIcon = Icons.Outlined.Shield,
                onBack = onBackClick,
                overline = serverName,
                scrollBehavior = heroScroll,
                actions = { SaveAction(onClick = onSave, enabled = isDirty) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    onApprovePasswordResetClick = { viewModel.decidePasswordReset(it.id, approved = true) },
                    onDenyPasswordResetClick = { resetToDenyState.value = it },
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onBackupClick = onBackupClick,
                    onImportClick = onImportClick,
                    onUploadBooksClick = onUploadBooksClick,
                    onInboxClick = onInboxClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    onOrganizeClick = onOrganizeClick,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    onRemoteUrlChange = onRemoteUrlChange,
                    holdNewBooksForReview = holdNewBooksForReview,
                    onHoldNewBooksForReviewChange = onHoldNewBooksForReviewChange,
                    pushNotificationsEnabled = pushNotificationsEnabled,
                    onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
                    ratingSources = ratingSources,
                    onRatingSourceEnabledChange = onRatingSourceEnabledChange,
                    hardcoverSource = hardcoverSource,
                    hardcoverTokenSave = hardcoverTokenSave,
                    hardcoverActions = hardcoverActions,
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
        resetToDenyState = resetToDenyState,
    )

    // The one-time reset code. It is returned exactly once, by the approval call, and by no
    // other surface — there is no way to retrieve it later. `onDismissRequest` is a no-op:
    // dismissal is only via the explicit "Done" button in the dialog itself, so a stray
    // back-press or outside tap can never lose it before the admin has read/copied it.
    (state as? AdminUiState.Ready)?.resetCodeToConvey?.let { code ->
        PasswordResetCodeDialog(
            code = code,
            recipientName = (state as AdminUiState.Ready).resetCodeRecipientName,
            onCopyClick = {
                copyToClipboard(code)
                scope.launch {
                    snackbarHostState.showSnackbar(resetCodeCopiedMessage)
                }
            },
            onDone = { viewModel.dismissResetCode() },
        )
    }
}

@Composable
private fun AdminConfirmationDialogs(
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

    // Deny password-reset confirmation dialog
    resetToDeny?.let { request ->
        ListenUpDestructiveDialog(
            onDismissRequest = { resetToDeny = null },
            title = stringResource(Res.string.admin_deny_reset),
            text = "Are you sure you want to deny the password reset for ${request.displayName}?",
            confirmText = stringResource(Res.string.common_deny),
            onConfirm = {
                viewModel.decidePasswordReset(request.id, approved = false)
                resetToDeny = null
            },
            onDismiss = { resetToDeny = null },
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
    onApprovePasswordResetClick: (PasswordResetRequest) -> Unit,
    onDenyPasswordResetClick: (PasswordResetRequest) -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onBackupClick: () -> Unit,
    onImportClick: () -> Unit,
    onUploadBooksClick: () -> Unit,
    onInboxClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    onOrganizeClick: () -> Unit,
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    holdNewBooksForReview: Boolean,
    onHoldNewBooksForReviewChange: (Boolean) -> Unit,
    pushNotificationsEnabled: Boolean,
    onPushNotificationsEnabledChange: (Boolean) -> Unit,
    ratingSources: List<RatingSourceStatus>,
    onRatingSourceEnabledChange: (ExternalRatingSource, Boolean) -> Unit,
    hardcoverSource: HardcoverSourceStatus?,
    hardcoverTokenSave: HardcoverTokenSave,
    hardcoverActions: HardcoverSourceActions,
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
            onApprovePasswordResetClick = onApprovePasswordResetClick,
            onDenyPasswordResetClick = onDenyPasswordResetClick,
            onInviteClick = onInviteClick,
            onCollectionsClick = onCollectionsClick,
            onCategoriesClick = onCategoriesClick,
            onBackupClick = onBackupClick,
            onImportClick = onImportClick,
            onUploadBooksClick = onUploadBooksClick,
            onInboxClick = onInboxClick,
            onLibrarySettingsClick = onLibrarySettingsClick,
            onOrganizeClick = onOrganizeClick,
            serverName = serverName,
            onServerNameChange = onServerNameChange,
            remoteUrl = remoteUrl,
            onRemoteUrlChange = onRemoteUrlChange,
            holdNewBooksForReview = holdNewBooksForReview,
            onHoldNewBooksForReviewChange = onHoldNewBooksForReviewChange,
            pushNotificationsEnabled = pushNotificationsEnabled,
            onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
            ratingSources = ratingSources,
            onRatingSourceEnabledChange = onRatingSourceEnabledChange,
            hardcoverSource = hardcoverSource,
            hardcoverTokenSave = hardcoverTokenSave,
            hardcoverActions = hardcoverActions,
            modifier = modifier,
        )
    } else {
        LazyColumn(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            contentPadding = PaddingValues(top = 24.dp),
        ) {
            item {
                ServerSettingsSection(
                    state = state,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    onRemoteUrlChange = onRemoteUrlChange,
                    holdNewBooksForReview = holdNewBooksForReview,
                    onHoldNewBooksForReviewChange = onHoldNewBooksForReviewChange,
                    pushNotificationsEnabled = pushNotificationsEnabled,
                    onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
                    onRegistrationPolicyChange = onRegistrationPolicyChange,
                )
            }

            item {
                RatingSourcesGroup(
                    sources = ratingSources,
                    onSourceEnabledChange = onRatingSourceEnabledChange,
                )
            }

            hardcoverSource?.let { source ->
                item {
                    HardcoverSourceGroup(status = source, tokenSave = hardcoverTokenSave, actions = hardcoverActions)
                }
            }

            usersSection(
                state = state,
                onUserClick = onUserClick,
                onDeleteUserClick = onDeleteUserClick,
                onApproveUserClick = onApproveUserClick,
                onDenyUserClick = onDenyUserClick,
                onCopyInviteClick = onCopyInviteClick,
                onRevokeInviteClick = onRevokeInviteClick,
                onApprovePasswordResetClick = onApprovePasswordResetClick,
                onDenyPasswordResetClick = onDenyPasswordResetClick,
                onInviteClick = onInviteClick,
            )

            item {
                ManagementSection(
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onBackupClick = onBackupClick,
                    onImportClick = onImportClick,
                    onUploadBooksClick = onUploadBooksClick,
                    onInboxClick = onInboxClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    onOrganizeClick = onOrganizeClick,
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
    onApprovePasswordResetClick: (PasswordResetRequest) -> Unit,
    onDenyPasswordResetClick: (PasswordResetRequest) -> Unit,
    onInviteClick: () -> Unit,
    onCollectionsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onBackupClick: () -> Unit,
    onImportClick: () -> Unit,
    onUploadBooksClick: () -> Unit,
    onInboxClick: () -> Unit,
    onLibrarySettingsClick: () -> Unit,
    onOrganizeClick: () -> Unit,
    serverName: String,
    onServerNameChange: (String) -> Unit,
    remoteUrl: String,
    onRemoteUrlChange: (String) -> Unit,
    holdNewBooksForReview: Boolean,
    onHoldNewBooksForReviewChange: (Boolean) -> Unit,
    pushNotificationsEnabled: Boolean,
    onPushNotificationsEnabledChange: (Boolean) -> Unit,
    ratingSources: List<RatingSourceStatus>,
    onRatingSourceEnabledChange: (ExternalRatingSource, Boolean) -> Unit,
    hardcoverSource: HardcoverSourceStatus?,
    hardcoverTokenSave: HardcoverTokenSave,
    hardcoverActions: HardcoverSourceActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = Spacing.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1.1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            contentPadding = PaddingValues(top = 24.dp),
        ) {
            item {
                ServerSettingsSection(
                    state = state,
                    serverName = serverName,
                    onServerNameChange = onServerNameChange,
                    remoteUrl = remoteUrl,
                    onRemoteUrlChange = onRemoteUrlChange,
                    holdNewBooksForReview = holdNewBooksForReview,
                    onHoldNewBooksForReviewChange = onHoldNewBooksForReviewChange,
                    pushNotificationsEnabled = pushNotificationsEnabled,
                    onPushNotificationsEnabledChange = onPushNotificationsEnabledChange,
                    onRegistrationPolicyChange = onRegistrationPolicyChange,
                )
            }

            item {
                RatingSourcesGroup(
                    sources = ratingSources,
                    onSourceEnabledChange = onRatingSourceEnabledChange,
                )
            }

            hardcoverSource?.let { source ->
                item {
                    HardcoverSourceGroup(status = source, tokenSave = hardcoverTokenSave, actions = hardcoverActions)
                }
            }

            usersSection(
                state = state,
                onUserClick = onUserClick,
                onDeleteUserClick = onDeleteUserClick,
                onApproveUserClick = onApproveUserClick,
                onDenyUserClick = onDenyUserClick,
                onCopyInviteClick = onCopyInviteClick,
                onRevokeInviteClick = onRevokeInviteClick,
                onApprovePasswordResetClick = onApprovePasswordResetClick,
                onDenyPasswordResetClick = onDenyPasswordResetClick,
                onInviteClick = onInviteClick,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
            contentPadding = PaddingValues(top = 24.dp),
        ) {
            item {
                ManagementSection(
                    onInviteClick = onInviteClick,
                    onCollectionsClick = onCollectionsClick,
                    onCategoriesClick = onCategoriesClick,
                    onBackupClick = onBackupClick,
                    onImportClick = onImportClick,
                    onUploadBooksClick = onUploadBooksClick,
                    onInboxClick = onInboxClick,
                    onLibrarySettingsClick = onLibrarySettingsClick,
                    onOrganizeClick = onOrganizeClick,
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
    holdNewBooksForReview: Boolean,
    onHoldNewBooksForReviewChange: (Boolean) -> Unit,
    pushNotificationsEnabled: Boolean,
    onPushNotificationsEnabledChange: (Boolean) -> Unit,
    onRegistrationPolicyChange: (RegistrationPolicy) -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_server_settings),
    ) {
        SectionSegment {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ListenUpTextField(
                    value = serverName,
                    onValueChange = onServerNameChange,
                    label = stringResource(Res.string.admin_server_name),
                    placeholder = stringResource(Res.string.connect_listenup_server),
                    leadingIcon = Icons.Outlined.Badge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                ListenUpTextField(
                    value = remoteUrl,
                    onValueChange = onRemoteUrlChange,
                    label = stringResource(Res.string.admin_remote_url),
                    placeholder = stringResource(Res.string.admin_remote_url_placeholder),
                    leadingIcon = Icons.Outlined.CloudDownload,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        }
        SectionSegment {
            RegistrationPolicyControl(
                policy = state.registrationPolicy,
                isToggling = state.isTogglingRegistrationPolicy,
                onChange = onRegistrationPolicyChange,
            )
        }
        SettingToggleRow(
            icon = Icons.Outlined.Inbox,
            title = stringResource(Res.string.admin_inbox_setting_title),
            subtitle = stringResource(Res.string.admin_inbox_setting_subtitle),
            checked = holdNewBooksForReview,
            onCheckedChange = onHoldNewBooksForReviewChange,
        )
        SettingToggleRow(
            icon = Icons.Outlined.Notifications,
            title = stringResource(Res.string.admin_push_setting_title),
            subtitle = stringResource(Res.string.admin_push_setting_subtitle),
            checked = pushNotificationsEnabled,
            onCheckedChange = onPushNotificationsEnabledChange,
        )
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
    val haptics = LocalHaptics.current
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
                    onClick = {
                        if (policy != option) {
                            haptics.selectionTick()
                            onChange(option)
                        }
                    },
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
// Rating sources section
// ---------------------------------------------------------------------------

/**
 * "Rating sources": one row per outside catalog the server can fetch a book's rating from, a
 * switch to enable/disable it, and a health line reporting its last fetch. Switching a source off
 * hides its scores at once everywhere (each of its rows flips `enabled`); switching it back on
 * brings them back.
 */
@Composable
internal fun RatingSourcesGroup(
    sources: List<RatingSourceStatus>,
    onSourceEnabledChange: (ExternalRatingSource, Boolean) -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_rating_sources_title),
    ) {
        SectionSegment {
            Text(
                text = stringResource(Res.string.admin_rating_sources_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.lg),
            )
        }
        sources.forEach { status ->
            RatingSourceRow(
                status = status,
                onEnabledChange = { enabled -> onSourceEnabledChange(status.source, enabled) },
            )
        }
    }
}

/**
 * One source: the whole row is its switch, and the subtitle says how it is doing. The health line
 * reads, first match wins: why it cannot run at all, then until when it has paused itself (a
 * future date, so an absolute one — "Paused until October 6, 2026"), then its last error, then
 * when it last fetched, then that it never has. Hardcover adds whose account it fetches with.
 * An unavailable source's switch stays operable: turning it off is still meaningful.
 */
@Composable
private fun RatingSourceRow(
    status: RatingSourceStatus,
    onEnabledChange: (Boolean) -> Unit,
) {
    val healthLine = ratingSourceHealthLine(status)
    val connectionLine =
        status.connectionUsername
            ?.takeIf { status.source == ExternalRatingSource.HARDCOVER }
            ?.let { stringResource(Res.string.admin_rating_source_using_connection, it) }
    SettingToggleRow(
        icon = Icons.Outlined.Star,
        title = ratingSourceLabel(status.source),
        subtitle = listOfNotNull(healthLine, connectionLine).joinToString("\n"),
        checked = status.enabled,
        onCheckedChange = onEnabledChange,
        modifier = Modifier.testTag("ratingSourceSwitch_${status.source.name}"),
    )
}

/** [status]'s one-line health, in the priority [RatingSourceRow] documents. */
@Composable
private fun ratingSourceHealthLine(status: RatingSourceStatus): String {
    val unavailable = status.unavailable
    val pausedUntil = status.pausedUntil
    val lastError = status.lastError
    val lastFetchedAt = status.lastFetchedAt
    return when {
        unavailable != null -> {
            stringResource(
                when (unavailable) {
                    RatingSourceUnavailable.NOT_CONFIGURED -> Res.string.admin_rating_source_not_configured
                    RatingSourceUnavailable.NO_CONNECTION -> Res.string.admin_rating_source_no_connection
                    RatingSourceUnavailable.UNKNOWN -> Res.string.admin_rating_source_unavailable
                },
            )
        }

        pausedUntil != null && lastError != null -> {
            stringResource(Res.string.admin_rating_source_paused, formatDateLong(pausedUntil), lastError)
        }

        pausedUntil != null -> {
            stringResource(Res.string.admin_rating_source_paused_plain, formatDateLong(pausedUntil))
        }

        lastError != null -> {
            stringResource(Res.string.admin_rating_source_error, lastError)
        }

        lastFetchedAt != null && isJustNow(lastFetchedAt) -> {
            stringResource(Res.string.admin_rating_source_last_fetched_just_now)
        }

        lastFetchedAt != null -> {
            stringResource(Res.string.admin_rating_source_last_fetched, relativeTime(lastFetchedAt))
        }

        else -> {
            stringResource(Res.string.admin_rating_source_never_fetched)
        }
    }
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
    onApprovePasswordResetClick: (PasswordResetRequest) -> Unit,
    onDenyPasswordResetClick: (PasswordResetRequest) -> Unit,
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

    // Task 15 (password-reset arc): minimal and deliberately temporary — a follow-up plan
    // relocates this into a rewritten people-management surface. Always visible (not gated
    // on emptiness like PendingInvitesGroup) so an admin knows where to look even when the
    // queue is empty.
    item {
        PasswordResetsGroup(
            state = state,
            onApproveClick = onApprovePasswordResetClick,
            onDenyClick = onDenyPasswordResetClick,
        )
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
            SectionSegment {
                Text(
                    text = stringResource(Res.string.common_no_items_found, "users"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        } else {
            state.users.forEach { user ->
                UserRow(
                    user = user,
                    isDeleting = state.deletingUserId == user.id,
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
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
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
                    IconButton(
                        onClick = {
                            haptics.press()
                            onDeleteClick()
                        },
                    ) {
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
    SectionGroup(
        label = stringResource(Res.string.admin_pending_registrations),
    ) {
        if (state.pendingUsers.isEmpty()) {
            SectionSegment {
                Text(
                    text = stringResource(Res.string.admin_no_pending_registrations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        } else {
            state.pendingUsers.forEach { user ->
                PendingUserRow(
                    user = user,
                    isApproving = state.approvingUserId == user.id,
                    isDenying = state.denyingUserId == user.id,
                    onApproveClick = { onApproveUserClick(user) },
                    onDenyClick = { onDenyUserClick(user) },
                )
            }
        }
    }
}

/**
 * Below this row width, the deny/approve pair collapses from labeled buttons to compact icon
 * buttons. Two labeled buttons overflow [SettingRow]'s weightless trailing slot on a phone and crush
 * the name/email column (which wraps, never truncates), so on narrow layouts the icons keep the row
 * uncramped; tablet/desktop widths keep the clearer labels.
 */
private val PENDING_ACTION_LABEL_MIN_WIDTH = 440.dp

@Composable
private fun PendingUserRow(
    user: AdminUserInfo,
    isApproving: Boolean,
    isDenying: Boolean,
    onApproveClick: () -> Unit,
    onDenyClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val name =
        user.displayName
            ?: "${user.firstName ?: ""} ${user.lastName ?: ""}".trim().ifEmpty { user.email }
    val denyLabel = stringResource(Res.string.common_deny)
    val approveLabel = stringResource(Res.string.common_approve)
    BoxWithConstraints {
        val compact = maxWidth < PENDING_ACTION_LABEL_MIN_WIDTH
        SettingRow(
            title = name,
            subtitle = user.email,
            // A pending registrant has no server-side public profile yet, so drive initials from
            // their name rather than the generic add-person glyph / an indefinite loading circle.
            leading = { UserAvatar(userId = user.id, size = AvatarSize.Medium, fallbackName = name) },
        ) {
            when {
                isApproving || isDenying -> {
                    ListenUpLoadingIndicatorSmall()
                }

                compact -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedIconButton(
                            onClick = {
                                haptics.press()
                                onDenyClick()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = denyLabel,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        FilledTonalIconButton(
                            onClick = {
                                haptics.press()
                                onApproveClick()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = approveLabel,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            border = listenUpOutlinedBorder(),
                            onClick = {
                                haptics.press()
                                onDenyClick()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(denyLabel)
                        }
                        FilledTonalButton(
                            onClick = {
                                haptics.press()
                                onApproveClick()
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(approveLabel)
                        }
                    }
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
    ) {
        state.pendingInvites.forEach { invite ->
            InviteRow(
                invite = invite,
                accent = accent,
                isRevoking = state.revokingInviteId == invite.id,
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
    onCopyClick: () -> Unit,
    onRevokeClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    SettingRow(
        icon = Icons.Outlined.PersonAdd,
        accent = accent,
        title = invite.name,
        subtitle = invite.email,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            RoleChip(label = invite.role.replaceFirstChar { it.uppercase() })
            IconButton(
                onClick = {
                    haptics.press()
                    onCopyClick()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(Res.string.admin_copy_link),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isRevoking) {
                ListenUpLoadingIndicatorSmall()
            } else {
                IconButton(
                    onClick = {
                        haptics.press()
                        onRevokeClick()
                    },
                ) {
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
// Password resets section (Task 15 — minimal and deliberately temporary; see
// docs/superpowers/plans/2026-08-09-password-reset.md for the follow-up plan that relocates
// this into a rewritten people-management surface)
// ---------------------------------------------------------------------------

@Composable
private fun PasswordResetsGroup(
    state: AdminUiState.Ready,
    onApproveClick: (PasswordResetRequest) -> Unit,
    onDenyClick: (PasswordResetRequest) -> Unit,
) {
    SectionGroup(
        label = stringResource(Res.string.admin_password_resets),
    ) {
        if (state.pendingPasswordResets.isEmpty()) {
            SectionSegment {
                Text(
                    text = stringResource(Res.string.admin_no_pending_password_resets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        } else {
            state.pendingPasswordResets.forEach { request ->
                PasswordResetRow(
                    request = request,
                    isDeciding = state.decidingPasswordResetId == request.id,
                    onApproveClick = { onApproveClick(request) },
                    onDenyClick = { onDenyClick(request) },
                )
            }
        }
    }
}

@Composable
private fun PasswordResetRow(
    request: PasswordResetRequest,
    isDeciding: Boolean,
    onApproveClick: () -> Unit,
    onDenyClick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val approveLabel = stringResource(Res.string.admin_approve_reset)
    val denyLabel = stringResource(Res.string.admin_deny_reset)
    SettingRow(
        title = request.displayName,
        subtitle = "${request.email} · ${relativeTime(request.requestedAt)}",
        leading = {
            UserAvatar(
                userId = request.userId.value,
                size = AvatarSize.Medium,
                fallbackName = request.displayName,
            )
        },
    ) {
        if (isDeciding) {
            ListenUpLoadingIndicatorSmall()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedIconButton(
                    onClick = {
                        haptics.press()
                        onDenyClick()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = denyLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        haptics.press()
                        onApproveClick()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = approveLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The one-time reset code, shown exactly once. Dismissal is **only** via the explicit "Done"
 * button — [DialogProperties] disables both back-press and outside-tap dismissal, and
 * `onDismissRequest` is a no-op, so the code cannot vanish before the admin has read or
 * copied it. The instruction text is load-bearing, not decoration: conveying the code out of
 * band (phone, in person, a chat the admin and requester already use) IS the identity check
 * this whole design rests on, so this dialog offers no way to send it back through the app.
 */
@Composable
private fun PasswordResetCodeDialog(
    code: String,
    recipientName: String?,
    onCopyClick: () -> Unit,
    onDone: () -> Unit,
) {
    val name = recipientName ?: stringResource(Res.string.admin_reset_code_recipient_fallback)
    ListenUpAlertDialog(
        onDismissRequest = {},
        title = stringResource(Res.string.admin_reset_code_title, name),
        confirmText = stringResource(Res.string.admin_reset_code_done),
        onConfirm = onDone,
        dismissText = stringResource(Res.string.common_copy),
        onDismiss = onCopyClick,
        icon = Icons.Outlined.Key,
        dismissIcon = Icons.Outlined.ContentCopy,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(Res.string.admin_reset_code_instruction, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            icon = Icons.Outlined.FolderOpen,
            onClick = onLibrarySettingsClick,
            containerColor = colors.secondaryContainer,
            badgeColor = colors.secondary,
            badgeContentColor = colors.onSecondary,
        )
        ActionTile(
            title = stringResource(Res.string.admin_upload_books),
            subtitle = stringResource(Res.string.admin_upload_books_subtitle),
            icon = Icons.Outlined.CloudUpload,
            onClick = onUploadBooksClick,
            containerColor = colors.primaryContainer,
            badgeColor = colors.primary,
            badgeContentColor = colors.onPrimary,
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
        // Unconditional. The hold-for-review setting governs whether *healthy* books wait here;
        // a book the scanner could not understand lands here regardless, so the way in must not
        // depend on a setting the admin may never have turned on.
        ActionTile(
            title = stringResource(Res.string.admin_inbox),
            subtitle = stringResource(Res.string.admin_inbox_subtitle),
            icon = Icons.Outlined.Inbox,
            onClick = onInboxClick,
            containerColor = colors.tertiaryContainer,
            badgeColor = colors.tertiary,
            badgeContentColor = colors.onTertiary,
        )
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
        ActionTile(
            title = stringResource(Res.string.import_title),
            subtitle = stringResource(Res.string.import_entry_subtitle),
            icon = Icons.Outlined.CloudDownload,
            onClick = onImportClick,
            containerColor = colors.surfaceContainerHigh,
            badgeColor = colors.tertiary,
            badgeContentColor = colors.onTertiary,
        )
    }
}
