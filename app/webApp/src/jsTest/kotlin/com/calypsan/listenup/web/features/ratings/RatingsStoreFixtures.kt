package com.calypsan.listenup.web.features.ratings

import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsEvent
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over a state that never changes, recording what it is asked to do — for specs. */
fun fixedBookRatings(
    state: BookRatingsUiState,
    onRate: (halfStars: Int, note: String?) -> Unit = { _, _ -> },
    onSetStars: (halfStars: Int) -> Unit = {},
    onClear: () -> Unit = {},
    onUndoClear: () -> Unit = {},
    events: Flow<BookRatingsEvent> = emptyFlow(),
    onRefreshExternal: () -> Unit = {},
): OpenBookRatings =
    {
        BookRatingsSession(
            state = MutableStateFlow(state),
            rate = onRate,
            setStars = onSetStars,
            clear = onClear,
            undoClear = onUndoClear,
            events = events,
            refreshExternal = onRefreshExternal,
            close = {},
        )
    }
