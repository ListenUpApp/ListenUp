package com.calypsan.listenup.client.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Pings whenever the discoverable-campfires set may have changed — on the server's broadcast
 * `SyncControl.CampfiresChanged` nudge (see `campfireDomain` in `RefreshedDomains.kt`).
 * `CampfireDiscoveryRepository` re-fetches `CampfireService.listOpenSessions()` on each ping.
 *
 * No slow-poll backstop (contrast [PresenceRefreshSignal]): campfire discovery is explicitly
 * ephemeral/best-effort (co-listening design spec), and every subscriber already re-fetches on
 * subscribe (screen entry). A dropped ping self-heals on the next foreground/reconnect edge via
 * the lifecycle-reconcile pass (`RefreshedDomainRouter.refreshAll`), so a dedicated poll ticker
 * would be unrequested complexity for a surface that already degrades gracefully.
 */
internal class CampfireRefreshSignal {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Hot stream of campfire-discovery-changed pings. */
    val signal: SharedFlow<Unit> = flow.asSharedFlow()

    /** Emit a campfires-changed ping (non-suspending, drops if no buffer — a missed ping is harmless). */
    fun ping() {
        flow.tryEmit(Unit)
    }
}
