package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchEvent
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A session over a state that never changes, recording what it is asked to do — for specs. The
 * gestures do not move the state, as the real ViewModel's do not until the server answers.
 */
fun fixedHardcoverMatch(
    state: HardcoverMatchUiState = HardcoverMatchUiState.Loading,
    events: Flow<HardcoverMatchEvent> = emptyFlow(),
    onPick: (Long) -> Unit = {},
    onRemoveMatch: () -> Unit = {},
    onUndoLink: () -> Unit = {},
    onOpen: (String) -> Unit = {},
): OpenHardcoverMatch =
    { bookId ->
        onOpen(bookId)
        HardcoverMatchSession(
            state = MutableStateFlow(state),
            events = events,
            onQueryChange = {},
            onSearch = {},
            onSearchFor = {},
            onPick = onPick,
            onRemoveMatch = onRemoveMatch,
            onUndoLink = onUndoLink,
            close = {},
        )
    }
