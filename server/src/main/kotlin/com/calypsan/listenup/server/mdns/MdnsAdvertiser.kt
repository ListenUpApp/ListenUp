package com.calypsan.listenup.server.mdns

/**
 * Advertises this server on the LAN via mDNS/DNS-SD. The swap seam: the production impl is the
 * pure-Kotlin [MulticastMdnsResponder], but a future dns-sd-kt/JmDNS impl could replace it without
 * touching callers.
 *
 * Both calls are best-effort and MUST NOT throw to the caller — advertisement is non-critical
 * (manual server-URL entry is the fallback), so a failure is logged and the server runs normally.
 */
interface MdnsAdvertiser {
    suspend fun start()

    suspend fun stop()
}
