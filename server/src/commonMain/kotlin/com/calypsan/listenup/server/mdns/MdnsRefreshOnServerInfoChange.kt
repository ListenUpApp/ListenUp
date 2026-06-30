package com.calypsan.listenup.server.mdns

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.sync.ChangeBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger("com.calypsan.listenup.server.mdns.MdnsRefreshOnServerInfoChange")

/**
 * Re-announce mDNS whenever the server's identity changes. The admin "update server settings" path
 * already broadcasts [SyncControl.ServerInfoChanged] on the [ChangeBus] (to nudge clients to re-fetch
 * server info); this collector reuses that same nudge to drive [MdnsAdvertiser.refresh], so a renamed
 * server re-advertises its new `name=`/`remote=` TXT to the LAN without a restart.
 *
 * Decoupled by design: the settings service stays unaware of mDNS — it just announces "server info
 * changed", and the advertisement layer reacts. Launched once at startup and collects for the lifetime
 * of [this] scope. [MdnsAdvertiser.refresh] is best-effort and a no-op when advertisement isn't running.
 */
fun CoroutineScope.launchMdnsRefreshOnServerInfoChange(
    changeBus: ChangeBus,
    advertiser: MdnsAdvertiser,
): Job =
    launch {
        changeBus.subscribeControl().collect { frame ->
            if (frame.control != SyncControl.ServerInfoChanged) return@collect
            runCatching { advertiser.refresh() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    log.warn(e) { "mDNS: failed to re-announce after a server-info change" }
                }
        }
    }
