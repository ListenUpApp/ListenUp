package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import com.calypsan.listenup.client.presentation.admin.AdminInboxViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open Inbox: what is waiting, what went wrong, and every gesture the page offers. */
class AdminInboxSession(
    val state: StateFlow<AdminInboxUiState>,
    val onToggleBook: (String) -> Unit,
    val onSelectAll: () -> Unit,
    val onClearSelection: () -> Unit,
    val onRelease: () -> Unit,
    val onDismissIssue: (String) -> Unit,
    val onClearError: () -> Unit,
    val onClearReleaseResult: () -> Unit,
    val onRetry: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its session. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenAdminInbox = () -> AdminInboxSession

/**
 * The production source: the shared [AdminInboxViewModel] over the started graph.
 *
 * No load call here, unlike [graphAdmin]: this ViewModel loads both halves and subscribes to the
 * admin event stream from its own `init`, so the inbox stays live while the page is open — a book
 * that finishes scanning appears without anyone pressing anything.
 */
fun graphAdminInbox(koin: Koin): OpenAdminInbox =
    {
        val viewModel = koin.get<AdminInboxViewModel>()
        val store = ViewModelStore().apply { put(INBOX_STORE_KEY, viewModel) }
        AdminInboxSession(
            state = viewModel.state,
            onToggleBook = viewModel::toggleBookSelection,
            onSelectAll = viewModel::selectAll,
            onClearSelection = viewModel::clearSelection,
            onRelease = viewModel::releaseSelected,
            onDismissIssue = viewModel::dismissScanIssue,
            onClearError = viewModel::clearError,
            onClearReleaseResult = viewModel::clearReleaseResult,
            onRetry = viewModel::loadInboxBooks,
            close = store::clear,
        )
    }

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

private const val INBOX_STORE_KEY = "admin-inbox"
