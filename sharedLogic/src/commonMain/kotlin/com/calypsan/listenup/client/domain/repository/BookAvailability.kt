package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow

/** Reactive "can the user play/download this book right now" state for the Book Detail screen. */
interface BookAvailability {
    fun observe(bookId: BookId): Flow<State>

    /**
     * Derived availability for one book.
     *
     * Attempt-first: [canPlay] and [canDownload] are never gated on server reachability —
     * [showServerWarning] is the non-blocking point-of-need hint instead.
     */
    data class State(
        val downloadStatus: BookDownloadStatus,
        val isPlaybackAvailable: Boolean,
        val canPlay: Boolean,
        val canDownload: Boolean,
        val showServerWarning: Boolean,
        val isWaitingForWifi: Boolean,
    )
}
