package com.calypsan.listenup.client.push

import com.calypsan.listenup.api.push.PushPayload

/**
 * Decides whether an arriving push should be rendered as a system notification, given whether the
 * app is currently in the foreground.
 *
 * Pure and unit-tested rather than inline in [ListenUpMessagingService], because the rule has a
 * genuine exception and getting it wrong is invisible: a suppressed notification looks exactly like
 * one that never arrived. Mirrors the `PlayerGestureMath` / `NowPlayingGestureMath` precedent.
 */
internal object PushForegroundPolicy {
    /**
     * Whether [payload] should be rendered.
     *
     * A foregrounded app normally skips the local notification — the in-app notification inbox
     * (bell + list) already shows the row, so the badge has moved by the time the banner would
     * render. [PushPayload.TestNotification] is the exception,
     * and has to be: it exists purely to prove a notification can reach this device, and it is
     * triggered from Settings — which means the app is necessarily in the foreground when it
     * arrives. Suppressing it guaranteed the one thing it was built to demonstrate could never be
     * demonstrated, while the UI cheerfully reported "sent".
     */
    fun shouldRender(
        payload: PushPayload?,
        appInForeground: Boolean,
    ): Boolean = payload is PushPayload.TestNotification || !appInForeground
}
