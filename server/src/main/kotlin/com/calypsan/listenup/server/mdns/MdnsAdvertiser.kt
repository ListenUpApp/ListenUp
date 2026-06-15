package com.calypsan.listenup.server.mdns

/**
 * Advertises this server on the LAN via mDNS/DNS-SD. The swap seam: the production impl is the
 * pure-Kotlin [MulticastMdnsResponder], but a future dns-sd-kt/JmDNS impl could replace it without
 * touching callers.
 *
 * All three calls are best-effort and MUST NOT throw to the caller — advertisement is non-critical
 * (manual server-URL entry is the fallback), so a failure is logged and the server runs normally.
 */
interface MdnsAdvertiser {
    suspend fun start()

    suspend fun stop()

    /**
     * Rebuild the advertised TXT record from its current source and re-announce, so a runtime change
     * to the server's identity (e.g. an admin renaming the server) reaches LAN clients without a
     * restart. No-op when advertisement was never started (mDNS disabled, or no multicast interface).
     */
    suspend fun refresh()
}
