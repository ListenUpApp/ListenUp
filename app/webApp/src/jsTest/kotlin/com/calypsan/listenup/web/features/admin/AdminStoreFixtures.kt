package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import kotlinx.coroutines.flow.MutableStateFlow

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
