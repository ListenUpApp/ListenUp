package com.calypsan.listenup.client.playback

import androidx.media3.common.Player

/**
 * The custom session command the playback service sends to its controllers after the idle timeout let go of the
 * local player. The app's own controller holds a process-lifetime binding, so [android.app.Service.stopSelf] alone
 * never destroys the service; this tells the app side the engine no longer holds its book (see
 * [MediaControllerHolder.onServiceCustomCommand]). Plain `STATE_IDLE` must never stand in for it: an idle player
 * with no items is also what a load looks like for a moment.
 */
internal const val COMMAND_IDLE_RELEASED = "com.calypsan.listenup.IDLE_RELEASED"

/**
 * Let go of an idle local [player]: record where the listener is, stop it and drop its items (freeing the decoder
 * and the notification), then tell the controllers. In that order, so the position is read while the player still
 * holds the book.
 */
internal fun releaseIdlePlayer(
    player: Player,
    savePosition: () -> Unit,
    announceRelease: () -> Unit,
) {
    savePosition()
    player.stop()
    player.clearMediaItems()
    announceRelease()
}
