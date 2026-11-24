package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.client.domain.model.PermissionPreset
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.client.presentation.admin.UserPermissionsUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** Builds [PermissionsPanelActions] with no-op defaults for specs. */
@Suppress("LongParameterList")
fun permissionsPanelActions(
    onSelectPreset: (PermissionPreset) -> Unit = {},
    onSetPermission: (Permission, Boolean) -> Unit = { _, _ -> },
    onRequestRole: (UserRole) -> Unit = {},
    onConfirmAdminPromotion: () -> Unit = {},
    onCancelAdminPromotion: () -> Unit = {},
    onDiscard: () -> Unit = {},
    onSave: () -> Unit = {},
    onRetry: () -> Unit = {},
): PermissionsPanelActions =
    PermissionsPanelActions(
        onSelectPreset = onSelectPreset,
        onSetPermission = onSetPermission,
        onRequestRole = onRequestRole,
        onConfirmAdminPromotion = onConfirmAdminPromotion,
        onCancelAdminPromotion = onCancelAdminPromotion,
        onDiscard = onDiscard,
        onSave = onSave,
        onRetry = onRetry,
    )

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedCreateInvite(
    state: CreateInviteUiState,
    onCreate: (String, String, Int) -> Unit = { _, _, _ -> },
    onClearError: () -> Unit = {},
    onReset: () -> Unit = {},
): OpenCreateInvite =
    {
        CreateInviteSession(
            state = MutableStateFlow(state),
            onCreate = onCreate,
            onClearError = onClearError,
            onReset = onReset,
            close = {},
        )
    }

/** A session over states that never change — the shape specs pass in place of the graph. */
fun fixedUserDetail(
    state: UserDetailUiState,
    permissions: UserPermissionsUiState = UserPermissionsUiState.Loading,
    actions: PermissionsPanelActions = permissionsPanelActions(),
    onOpen: (String) -> Unit = {},
): OpenUserDetail =
    { userId ->
        onOpen(userId)
        UserDetailSession(
            state = MutableStateFlow(state),
            permissions = MutableStateFlow(permissions),
            actions = actions,
            onRetry = {},
            close = {},
        )
    }
