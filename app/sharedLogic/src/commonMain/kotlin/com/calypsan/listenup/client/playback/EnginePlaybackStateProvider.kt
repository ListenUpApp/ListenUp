package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import kotlinx.coroutines.flow.StateFlow

/**
 * [PlaybackStateProvider] read off the shared player's [PlayerEngine], for a platform with no [PlaybackManager]:
 * iOS, whose engine is [NativePlayerEngine]. The book playing is the one the engine holds, and clearing playback
 * stops it.
 */
internal class EnginePlaybackStateProvider(
    private val engine: PlayerEngine,
) : PlaybackStateProvider {
    override val currentBookId: StateFlow<BookId?> get() = engine.loadedBookId

    override fun clearPlayback() = engine.stop()
}
