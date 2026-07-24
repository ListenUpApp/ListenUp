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
     * [canPlay]/[canDownload] gate honestly on the evidence-based reachability signal: they
     * disable only when the server is genuinely unreachable right now (device offline or the
     * latest transport evidence is a network failure) and re-enable the instant any traffic
     * proves otherwise. Unknown never blocks. [showServerWarning] is the matching
     * point-of-need hint.
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
