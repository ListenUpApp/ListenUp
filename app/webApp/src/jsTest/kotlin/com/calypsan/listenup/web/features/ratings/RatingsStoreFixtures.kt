package com.calypsan.listenup.web.features.ratings

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsEvent
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Renders [RatingsPanel] with no-op callback defaults for specs. */
@Composable
@Suppress("LongParameterList")
internal fun testRatingsPanel(
    state: BookRatingsUiState,
    onSetStars: (halfStars: Int) -> Unit = {},
    onRate: (halfStars: Int, note: String?) -> Unit = { _, _ -> },
    onClear: () -> Unit = {},
    onRefreshExternal: () -> Unit = {},
    nowMs: Long = 0L,
    starsFocusRequest: Int = 0,
    isFinished: Boolean = false,
) {
    RatingsPanel(
        state = state,
        onSetStars = onSetStars,
        onRate = onRate,
        onClear = onClear,
        onRefreshExternal = onRefreshExternal,
        nowMs = nowMs,
        starsFocusRequest = starsFocusRequest,
        isFinished = isFinished,
    )
}

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
