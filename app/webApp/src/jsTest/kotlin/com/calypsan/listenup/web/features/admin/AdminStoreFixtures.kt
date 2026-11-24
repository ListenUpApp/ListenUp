package com.calypsan.listenup.web.features.admin

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** Renders [AdminPage] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testAdminPage(
    state: AdminUiState,
    nowMs: Long = 0L,
    onApproveUser: (String) -> Unit = {},
    onDenyUser: (String) -> Unit = {},
    onDeleteUser: (String) -> Unit = {},
    onRevokeInvite: (String) -> Unit = {},
    onDecidePasswordReset: (String, Boolean) -> Unit = { _, _ -> },
    onDismissResetCode: () -> Unit = {},
    onSetRegistrationPolicy: (RegistrationPolicy) -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCopyInviteLink: (String) -> Unit = {},
    onOpenLibrarySettings: () -> Unit = {},
    onOpenInbox: () -> Unit = {},
    onOpenServerSettings: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    onOpenBackups: () -> Unit = {},
    onOpenImports: () -> Unit = {},
    onOpenInvite: () -> Unit = {},
    onOpenUpload: () -> Unit = {},
    onOpenOrganize: () -> Unit = {},
    onOpenUser: (String) -> Unit = {},
    landOnPending: Boolean = false,
) {
    AdminPage(
        state = state,
        nowMs = nowMs,
        onApproveUser = onApproveUser,
        onDenyUser = onDenyUser,
        onDeleteUser = onDeleteUser,
        onRevokeInvite = onRevokeInvite,
        onDecidePasswordReset = onDecidePasswordReset,
        onDismissResetCode = onDismissResetCode,
        onSetRegistrationPolicy = onSetRegistrationPolicy,
        onClearError = onClearError,
        onRetry = onRetry,
        onCopyInviteLink = onCopyInviteLink,
        onOpenLibrarySettings = onOpenLibrarySettings,
        onOpenInbox = onOpenInbox,
        onOpenServerSettings = onOpenServerSettings,
        onOpenCategories = onOpenCategories,
        onOpenCollections = onOpenCollections,
        onOpenBackups = onOpenBackups,
        onOpenImports = onOpenImports,
        onOpenInvite = onOpenInvite,
        onOpenUpload = onOpenUpload,
        onOpenOrganize = onOpenOrganize,
        onOpenUser = onOpenUser,
        landOnPending = landOnPending,
    )
}

/** Renders [AdminInboxPage] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testAdminInboxPage(
    state: AdminInboxUiState,
    onToggleBook: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onRelease: () -> Unit = {},
    onDismissIssue: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    onClearReleaseResult: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
    onOpenBookEdit: (String) -> Unit = {},
    onOpenMatch: (String) -> Unit = {},
    onOpenBook: (String) -> Unit = {},
) {
    AdminInboxPage(
        state = state,
        onToggleBook = onToggleBook,
        onSelectAll = onSelectAll,
        onClearSelection = onClearSelection,
        onRelease = onRelease,
        onDismissIssue = onDismissIssue,
        onClearError = onClearError,
        onClearReleaseResult = onClearReleaseResult,
        onRetry = onRetry,
        onOpenAdmin = onOpenAdmin,
        onOpenBookEdit = onOpenBookEdit,
        onOpenMatch = onOpenMatch,
        onOpenBook = onOpenBook,
    )
}

/** Renders [ServerSettingsPage] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testServerSettingsPage(
    state: AdminSettingsUiState,
    onServerName: (String) -> Unit = {},
    onRemoteUrl: (String) -> Unit = {},
    onHoldNewBooks: (Boolean) -> Unit = {},
    onPushNotifications: (Boolean) -> Unit = {},
    onSetRatingSourceEnabled: (ExternalRatingSource, Boolean) -> Unit = { _, _ -> },
    onMetadataRegion: (String) -> Unit = {},
    onSaveHardcoverToken: (String) -> Unit = {},
    onRemoveHardcoverToken: () -> Unit = {},
    onHardcoverMetadata: (Boolean) -> Unit = {},
    onClearHardcoverTokenError: () -> Unit = {},
    onSave: () -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
    nowMs: Long = 0L,
) {
    ServerSettingsPage(
        state = state,
        onServerName = onServerName,
        onRemoteUrl = onRemoteUrl,
        onHoldNewBooks = onHoldNewBooks,
        onPushNotifications = onPushNotifications,
        onSetRatingSourceEnabled = onSetRatingSourceEnabled,
        onMetadataRegion = onMetadataRegion,
        onSaveHardcoverToken = onSaveHardcoverToken,
        onRemoveHardcoverToken = onRemoveHardcoverToken,
        onHardcoverMetadata = onHardcoverMetadata,
        onClearHardcoverTokenError = onClearHardcoverTokenError,
        onSave = onSave,
        onClearError = onClearError,
        onRetry = onRetry,
        onOpenAdmin = onOpenAdmin,
        nowMs = nowMs,
    )
}

/** A session over a state that never changes — the shape specs pass in place of the graph. */
@Suppress("LongParameterList")
fun fixedAdmin(
    state: AdminUiState = AdminUiState.Loading,
    onApproveUser: (String) -> Unit = {},
    onDenyUser: (String) -> Unit = {},
    onDeleteUser: (String) -> Unit = {},
    onRevokeInvite: (String) -> Unit = {},
    onDecidePasswordReset: (String, Boolean) -> Unit = { _, _ -> },
    onDismissResetCode: () -> Unit = {},
    onSetRegistrationPolicy: (RegistrationPolicy) -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
): OpenAdmin =
    {
        AdminSession(
            state = MutableStateFlow(state),
            onApproveUser = onApproveUser,
            onDenyUser = onDenyUser,
            onDeleteUser = onDeleteUser,
            onRevokeInvite = onRevokeInvite,
            onDecidePasswordReset = onDecidePasswordReset,
            onDismissResetCode = onDismissResetCode,
            onSetRegistrationPolicy = onSetRegistrationPolicy,
            onClearError = onClearError,
            onRetry = onRetry,
            close = {},
        )
    }
