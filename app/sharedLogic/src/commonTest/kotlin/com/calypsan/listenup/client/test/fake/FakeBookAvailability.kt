package com.calypsan.listenup.client.test.fake

import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [BookAvailability] whose one [stateFlow] answers for every book; defaults to streamable, not downloaded. */
class FakeBookAvailability(
    initial: BookAvailability.State =
        BookAvailability.State(
            downloadStatus = BookDownloadStatus.NotDownloaded(""),
            isPlaybackAvailable = true,
            canPlay = true,
            canDownload = false,
            showServerWarning = false,
            isWaitingForWifi = false,
        ),
) : BookAvailability {
    val stateFlow = MutableStateFlow(initial)

    override fun observe(bookId: BookId): Flow<BookAvailability.State> = stateFlow
}
