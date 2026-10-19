package com.calypsan.listenup.client.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * The loaded book's playhead, live: what the mini player and Now Playing show, ahead of the saved position (which is
 * written on a timer). Book Detail reads it for the loaded book so its current chapter keeps up with the player.
 */
interface LoadedBookPlayhead {
    /** Book-relative playhead in ms: the engine's while it holds the book, else the pending start or saved position. */
    val positionMs: StateFlow<Long>
}
