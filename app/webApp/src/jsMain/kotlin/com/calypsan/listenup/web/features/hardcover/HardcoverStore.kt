package com.calypsan.listenup.web.features.hardcover

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksEvent
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksViewModel
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsEvent
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open Hardcover screen: the connection, its one-shot effects, the seven gestures, the kept-off list
 * (#1541), and the teardown.
 *
 * [onOpenVerificationPage] is wired for completeness but the page does not call it. On web the
 * Linking phase carries a real `<a target="_blank">` to the same pre-filled URL, and a click on an
 * anchor is the one open a popup blocker never stops — routing that click through the ViewModel
 * would turn it into a scripted `window.open` after the fact, which is exactly what gets blocked.
 */
class HardcoverSession(
    val state: StateFlow<HardcoverSettingsUiState>,
    val events: Flow<HardcoverSettingsEvent>,
    val onConnect: () -> Unit,
    val onOpenVerificationPage: () -> Unit,
    val onDisconnect: () -> Unit,
    val onSyncNow: () -> Unit,
    val onSetShareMode: (HardcoverShareMode) -> Unit,
    val onSendHistory: () -> Unit,
    val onDismissHistory: () -> Unit,
    val keptOff: StateFlow<KeptOffBooksUiState>,
    val keptOffEvents: Flow<KeptOffBooksEvent>,
    val onSyncAgain: (bookId: String) -> Unit,
    val close: () -> Unit,
)

/** How the page gets its session. */
typealias OpenHardcover = () -> HardcoverSession

/** The production source: the shared [HardcoverSettingsViewModel] over the started graph. */
fun graphHardcover(koin: Koin): OpenHardcover =
    {
        val viewModel = koin.get<HardcoverSettingsViewModel>()
        val keptOff = koin.get<KeptOffBooksViewModel>()
        val store =
            ViewModelStore().apply {
                put(HARDCOVER_STORE_KEY, viewModel)
                put(KEPT_OFF_STORE_KEY, keptOff)
            }
        HardcoverSession(
            state = viewModel.uiState,
            events = viewModel.events,
            onConnect = viewModel::connect,
            onOpenVerificationPage = viewModel::openVerificationPage,
            onDisconnect = viewModel::disconnect,
            onSyncNow = viewModel::syncNow,
            onSetShareMode = viewModel::setShareMode,
            onSendHistory = viewModel::sendHistory,
            onDismissHistory = viewModel::dismissHistory,
            keptOff = keptOff.uiState,
            keptOffEvents = keptOff.events,
            onSyncAgain = keptOff::syncAgain,
            close = store::clear,
        )
    }

/**
 * A session over a state that never changes — the shape specs pass in place of the graph.
 *
 * The gestures record but do not move the state: the real ViewModel never moves it either (the
 * server's stream does), and a fixture that did would let a page ignoring its state still pass.
 */
fun fixedHardcover(
    state: HardcoverSettingsUiState = HardcoverSettingsUiState.Loading,
    events: Flow<HardcoverSettingsEvent> = emptyFlow(),
    onConnect: () -> Unit = {},
    onOpenVerificationPage: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onSetShareMode: (HardcoverShareMode) -> Unit = {},
    onSendHistory: () -> Unit = {},
    onDismissHistory: () -> Unit = {},
    keptOff: KeptOffBooksUiState = KeptOffBooksUiState.Loading,
    keptOffEvents: Flow<KeptOffBooksEvent> = emptyFlow(),
    onSyncAgain: (bookId: String) -> Unit = {},
): OpenHardcover =
    {
        HardcoverSession(
            state = MutableStateFlow(state),
            events = events,
            onConnect = onConnect,
            onOpenVerificationPage = onOpenVerificationPage,
            onDisconnect = onDisconnect,
            onSyncNow = onSyncNow,
            onSetShareMode = onSetShareMode,
            onSendHistory = onSendHistory,
            onDismissHistory = onDismissHistory,
            keptOff = MutableStateFlow(keptOff),
            keptOffEvents = keptOffEvents,
            onSyncAgain = onSyncAgain,
            close = {},
        )
    }

private const val HARDCOVER_STORE_KEY = "hardcover"
private const val KEPT_OFF_STORE_KEY = "hardcover-kept-off"
