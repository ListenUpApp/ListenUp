package com.calypsan.listenup.server.mdns

/**
 * True for interface names that are unambiguously virtual / non-LAN (docker & VM bridges, VPN and
 * tunnel devices) and must not be advertised over mDNS. Intentionally narrow: it never matches a
 * bare `br*` so a Linux host bridging its real LAN (`br0`, `br-lan`) keeps advertising. VPNs are also
 * caught by the point-to-point flag at the call site; this covers the bridge-style ones (docker0,
 * tailscale0, zerotier) that aren't point-to-point.
 */
internal fun isVirtualInterfaceName(name: String): Boolean {
    val n = name.lowercase()
    return VIRTUAL_INTERFACE_PREFIXES.any { n.startsWith(it) }
}

private val VIRTUAL_INTERFACE_PREFIXES =
    listOf(
        "docker",
        "veth",
        "virbr",
        "vmnet",
        "vboxnet",
        "tailscale",
        "zt",
        "tun",
        "tap",
        "utun",
        "wg",
        "ipsec",
        "gif",
        "stf",
    )
