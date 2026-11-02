package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes, recording Remove match and the switch — for specs. */
fun fixedBookHardcover(
    state: BookHardcoverUiState = BookHardcoverUiState.Hidden,
    onRemoveMatch: () -> Unit = {},
    onSetSynced: (Boolean) -> Unit = {},
): OpenBookHardcover =
    {
        BookHardcoverSession(
            MutableStateFlow(state),
            onRemoveMatch = onRemoveMatch,
            onSetSynced = onSetSynced,
            close = {},
        )
    }
