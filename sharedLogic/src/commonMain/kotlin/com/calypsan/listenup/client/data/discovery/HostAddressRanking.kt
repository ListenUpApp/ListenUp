package com.calypsan.listenup.client.data.discovery

/**
 * Order resolved mDNS addresses best-first for connection attempts.
 *
 * A multi-homed server answers discovery with several addresses, and some are unroutable from a
 * given client: a host's VPN CGNAT (`100.64.0.0/10`), IPv4 link-local (`169.254.0.0/16`), loopback,
 * or IPv6 link-local (`fe80::/10`). An address alone can't tell us which LAN is actually reachable
 * from here, so [DiscoveredServer] keeps them ALL and the connect path tries each in turn — but
 * trying the likely-good ones first keeps the happy path fast. The sort is stable, so the server's
 * announced order is preserved within a tier.
 */
internal fun rankHostAddresses(addresses: List<String>): List<String> = addresses.distinct().sortedBy(::addressTier)

/** Lower = tried first. Routable IPv4 → routable IPv6 → CGNAT (VPN) → link-local/loopback. */
private fun addressTier(address: String): Int {
    val a = address.lowercase()
    val isIpv6 = ":" in a
    return when {
        a.startsWith("127.") || a == "::1" || a.startsWith("fe80:") || a.startsWith("169.254.") -> TIER_UNREACHABLE
        isCarrierGradeNat(a) -> TIER_CGNAT
        isIpv6 -> TIER_IPV6
        else -> TIER_IPV4
    }
}

/** `100.64.0.0/10` — shared address space (RFC 6598), used by Tailscale and CGNAT; rarely LAN-routable. */
private fun isCarrierGradeNat(address: String): Boolean {
    val octets = address.split(".")
    if (octets.size != 4) return false
    val first = octets[0].toIntOrNull() ?: return false
    val second = octets[1].toIntOrNull() ?: return false
    return first == 100 && second in 64..127
}

private const val TIER_IPV4 = 0
private const val TIER_IPV6 = 1
private const val TIER_CGNAT = 2
private const val TIER_UNREACHABLE = 3
