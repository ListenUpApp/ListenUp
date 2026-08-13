package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long a play request must stay in flight before the UI-facing "preparing" signal appears —
 * see [preparingBookIdUiFlow]. Below this, a fast prepare (already-downloaded book, warm
 * connection) never flashes a spinner.
 */
internal val PREPARING_UI_DELAY = 250.milliseconds

/**
 * Derives the UI-facing view of a play-in-flight signal such as [PlaybackManager.preparingBookId]:
 * a non-null value is held back for [debounce] before it is surfaced, so a prepare that resolves
 * quickly never flashes a spinner. A transition to null (prepare finished, failed, or superseded by
 * another book) propagates immediately — the spinner must never linger past the real state.
 *
 * Mirrors [DefaultPlaybackBandwidthCoordinator]'s `transformLatest` + `delay` shape, inverted: that
 * coordinator delays the *release* edge and fires the *acquire* edge instantly; this delays the
 * acquire edge and fires the release edge instantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun preparingBookIdUiFlow(
    source: Flow<BookId?>,
    debounce: Duration = PREPARING_UI_DELAY,
): Flow<BookId?> =
    source
        .transformLatest { bookId ->
            if (bookId == null) {
                emit(null)
            } else {
                delay(debounce)
                emit(bookId)
            }
        }.distinctUntilChanged()
