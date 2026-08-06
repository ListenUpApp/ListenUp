package com.calypsan.listenup.client.playback

/** What the service should do when the user swipes the app away. */
internal enum class TaskRemovedAction {
    /** Nothing left to hold on to — release the service now. */
    STOP_SERVICE,

    /** Leave the notification up so playback can be resumed, but start counting down. */
    ARM_IDLE_TIMER,

    /** Leave the service exactly as it is. */
    KEEP_ALIVE,
}

/**
 * Decides what swiping the app away should do to the playback service.
 *
 * Swiping away is not "stop playing" — it is "put the app away". The notification is meant to
 * survive it so the listener can pick the book back up, which is why the default is to arm the
 * idle timer rather than stop.
 *
 * This has to be decided from state the caller already holds, **not** from whether an idle timer
 * happens to be armed. `onTaskRemoved` requests a pause immediately before deciding, and Media3
 * delivers the resulting `onIsPlayingChanged` asynchronously — so the timer that pause arms does
 * not exist yet at decision time. Reading it inverted the behaviour: swiping away mid-listen
 * stopped the service outright, while swiping away when already paused survived.
 *
 * A cast session is left strictly alone. The audio is playing on another device and has nothing
 * to do with the app's task; a timer armed here would end it on a countdown the listener never
 * asked for. (This mirrors `startIdleTimer`, which already stands down while casting.)
 */
internal fun taskRemovedActionFor(
    hasPlayer: Boolean,
    casting: Boolean,
): TaskRemovedAction =
    when {
        casting -> TaskRemovedAction.KEEP_ALIVE
        !hasPlayer -> TaskRemovedAction.STOP_SERVICE
        else -> TaskRemovedAction.ARM_IDLE_TIMER
    }
