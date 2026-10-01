package com.calypsan.listenup.web.features.devices

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import com.calypsan.listenup.client.presentation.settings.DevicesViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Devices session: where you are signed in, and the two ways to stop being somewhere else.
 * Neither signs out the device it is used on — that is the account menu's Sign out.
 */
class DevicesSession(
    val state: StateFlow<DevicesUiState>,
    val onRevoke: (String) -> Unit,
    val onSignOutOthers: () -> Unit,
    val onRetry: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its session. */
typealias OpenDevices = () -> DevicesSession

/** The production source: the shared [DevicesViewModel] over the started graph. */
fun graphDevices(koin: Koin): OpenDevices =
    {
        val viewModel = koin.get<DevicesViewModel>()
        val store = ViewModelStore().apply { put(DEVICES_STORE_KEY, viewModel) }
        DevicesSession(
            state = viewModel.uiState,
            onRevoke = viewModel::revokeDevice,
            onSignOutOthers = viewModel::signOutOtherDevices,
            onRetry = viewModel::retry,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedDevices(
    state: DevicesUiState = DevicesUiState.Loading,
    onRevoke: (String) -> Unit = {},
    onSignOutOthers: () -> Unit = {},
    onRetry: () -> Unit = {},
): OpenDevices =
    {
        DevicesSession(
            state = MutableStateFlow(state),
            onRevoke = onRevoke,
            onSignOutOthers = onSignOutOthers,
            onRetry = onRetry,
            close = {},
        )
    }

private const val DEVICES_STORE_KEY = "devices"
