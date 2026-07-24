package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.StateFlow

/** Read/control seam for code that must observe or clear active playback. */
interface PlaybackStateProvider {
    val currentBookId: StateFlow<BookId?>

    fun clearPlayback()
}
