package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.presentation.admin.AdminSettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open Server Settings screen, every change it accepts, and the teardown for it. */
class ServerSettingsSession(
    val state: StateFlow<AdminSettingsUiState>,
    val onServerName: (String) -> Unit,
    val onRemoteUrl: (String) -> Unit,
    val onHoldNewBooks: (Boolean) -> Unit,
    val onPushNotifications: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onClearError: () -> Unit,
    val onRetry: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenServerSettings = () -> ServerSettingsSession

/**
 * The production source: the shared [AdminSettingsViewModel] over the started graph.
 *
 * `loadSettings` fires here, as Admin's own session does: the load is what the session IS, and
 * leaving it to the page means every future caller has to remember the same two-step.
 */
fun graphServerSettings(koin: Koin): OpenServerSettings =
    {
        val viewModel = koin.get<AdminSettingsViewModel>()
        val store = ViewModelStore().apply { put(SERVER_SETTINGS_STORE_KEY, viewModel) }
        viewModel.loadSettings()
        ServerSettingsSession(
            state = viewModel.state,
            onServerName = viewModel::setServerName,
            onRemoteUrl = viewModel::setRemoteUrl,
            onHoldNewBooks = viewModel::setHoldNewBooksForReview,
            onPushNotifications = viewModel::setPushNotificationsEnabled,
            onSave = viewModel::saveAll,
            onClearError = viewModel::clearError,
            onRetry = viewModel::loadSettings,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedServerSettings(
    state: AdminSettingsUiState = AdminSettingsUiState.Loading,
    onServerName: (String) -> Unit = {},
    onRemoteUrl: (String) -> Unit = {},
    onHoldNewBooks: (Boolean) -> Unit = {},
    onPushNotifications: (Boolean) -> Unit = {},
    onSave: () -> Unit = {},
    onClearError: () -> Unit = {},
    onRetry: () -> Unit = {},
): OpenServerSettings =
    {
        ServerSettingsSession(
            state = MutableStateFlow(state),
            onServerName = onServerName,
            onRemoteUrl = onRemoteUrl,
            onHoldNewBooks = onHoldNewBooks,
            onPushNotifications = onPushNotifications,
            onSave = onSave,
            onClearError = onClearError,
            onRetry = onRetry,
            close = {},
        )
    }

private const val SERVER_SETTINGS_STORE_KEY = "server-settings"
