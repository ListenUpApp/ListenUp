package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverShareMode
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksEvent
import com.calypsan.listenup.client.presentation.hardcover.KeptOffBooksUiState
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsEvent
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

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
