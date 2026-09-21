package com.calypsan.listenup.client.handoff

import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The book currently on screen, or null when the reader is somewhere else.
 *
 * ⛔ Exists only because a handoff has to be answered from OUTSIDE the composition. Continue On
 * calls back on the Activity, which cannot ask Compose what is on screen — so the screen tells this
 * holder while it is shown, and the Activity reads it. Sibling in spirit to `DeepLinkManager`, which
 * is the same shape in the other direction: one holds what we are about to navigate to, this holds
 * where we already are.
 *
 * Deliberately NOT a navigation mirror. It answers one question for one caller; a general
 * "current route" store would invite every feature to read nav state out of band, which is how a
 * navigation layer stops being the single source of truth.
 */
class ViewedBookTracker {
    private val viewed = MutableStateFlow<BookId?>(null)

    /** The book on screen, if a book screen is showing. */
    val viewedBookId: StateFlow<BookId?> = viewed.asStateFlow()

    /** Called by a book screen when it appears. */
    fun onBookShown(bookId: BookId) {
        viewed.value = bookId
    }

    /**
     * Called by a book screen when it goes away.
     *
     * Takes the id so a screen leaving late cannot clear a newer screen's claim — two book pages in
     * quick succession would otherwise race, and the second one's `onBookShown` would be wiped by
     * the first one's disposal.
     */
    fun onBookHidden(bookId: BookId) {
        viewed.compareAndSet(expect = bookId, update = null)
    }
}
