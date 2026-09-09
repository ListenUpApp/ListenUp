package com.calypsan.listenup.client.playback

/** What [PlaybackService]'s player listener should do with a reported playback error. */
internal enum class PlayerErrorAction {
    /** A live player is attached — hand the error to the recovery handler. */
    RECOVER,

    /** The player is already released; there is nothing to recover onto. */
    NOTHING_TO_RECOVER,
}

/**
 * Decides how to treat a playback error, from state the listener holds synchronously.
 *
 * This has to be decided BEFORE the recovery coroutine starts. `serviceScope` runs on plain
 * `Dispatchers.Main` (not `Main.immediate`), so a `launch` body executes on a later looper
 * turn — and `onDestroy` nulls `player` one statement before it cancels the scope. Reading
 * the player inside the coroutine (the old `player!!`) could therefore dereference null and
 * take the playback process down with it.
 */
internal fun playerErrorActionFor(hasPlayer: Boolean): PlayerErrorAction =
    if (hasPlayer) PlayerErrorAction.RECOVER else PlayerErrorAction.NOTHING_TO_RECOVER
