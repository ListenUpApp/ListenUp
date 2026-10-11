package com.calypsan.listenup.client

/**
 * Whether `onCreate` should act on the activity's launch intent.
 *
 * `getIntent()` keeps returning the intent that first started the activity, so a recreated
 * instance sees the same shortcut, push tap or share link again. The action it carries was parked
 * in a process-wide holder the first time and has usually been consumed since; acting on it again
 * replays it. A relaunch from Recents re-delivers the original intent the same way.
 */
internal fun shouldHandleLaunchIntent(
    isRestoredInstance: Boolean,
    launchedFromHistory: Boolean,
): Boolean = !isRestoredInstance && !launchedFromHistory
