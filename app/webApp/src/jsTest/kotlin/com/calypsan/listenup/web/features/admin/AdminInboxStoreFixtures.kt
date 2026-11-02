package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedAdminInbox(
    state: AdminInboxUiState = AdminInboxUiState.Loading,
    onToggleBook: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onRelease: () -> Unit = {},
    onDismissIssue: (String) -> Unit = {},
    onClearError: () -> Unit = {},
    onClearReleaseResult: () -> Unit = {},
    onRetry: () -> Unit = {},
): OpenAdminInbox =
    {
        AdminInboxSession(
            state = MutableStateFlow(state),
            onToggleBook = onToggleBook,
            onSelectAll = onSelectAll,
            onClearSelection = onClearSelection,
            onRelease = onRelease,
            onDismissIssue = onDismissIssue,
            onClearError = onClearError,
            onClearReleaseResult = onClearReleaseResult,
            onRetry = onRetry,
            close = {},
        )
    }
