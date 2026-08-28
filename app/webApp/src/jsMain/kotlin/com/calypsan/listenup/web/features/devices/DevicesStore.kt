package com.calypsan.listenup.web.features.devices

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import com.calypsan.listenup.client.presentation.settings.DevicesViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/** An open Devices session: where you are signed in, and the two ways to stop being. */
class DevicesSession(
    val state: StateFlow<DevicesUiState>,
    val onRevoke: (String) -> Unit,
    val onSignOutEverywhere: (onDone: () -> Unit) -> Unit,
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
            onSignOutEverywhere = viewModel::signOutEverywhere,
            onRetry = viewModel::retry,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedDevices(
    state: DevicesUiState = DevicesUiState.Loading,
    onRevoke: (String) -> Unit = {},
    onSignOutEverywhere: (onDone: () -> Unit) -> Unit = {},
    onRetry: () -> Unit = {},
): OpenDevices =
    {
        DevicesSession(
            state = MutableStateFlow(state),
            onRevoke = onRevoke,
            onSignOutEverywhere = onSignOutEverywhere,
            onRetry = onRetry,
            close = {},
        )
    }

private const val DEVICES_STORE_KEY = "devices"
