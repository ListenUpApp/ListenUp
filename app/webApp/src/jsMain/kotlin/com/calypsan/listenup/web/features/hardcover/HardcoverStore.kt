package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksEvent
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksViewModel
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsEvent
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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
    override val close: () -> Unit,
) : RouteSession

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

private const val HARDCOVER_STORE_KEY = "hardcover"
private const val KEPT_OFF_STORE_KEY = "hardcover-kept-off"
