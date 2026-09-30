package com.calypsan.listenup.client.data.repository

private const val MDNS_SUFFIX = ".local"
private const val IPV4_MAPPED_PREFIX = "::ffff:"
private const val IPV4_OCTETS = 4
private const val OCTET_MAX = 255
private const val HEXTET_RADIX = 16
private const val HEXTET_MAX_DIGITS = 4

// Private-use and link-local IPv4 ranges (RFC 1918, RFC 3927), as first-octet / second-octet bounds.
private const val PRIVATE_10 = 10
private const val PRIVATE_172 = 172
private const val PRIVATE_172_SECOND_MIN = 16
private const val PRIVATE_172_SECOND_MAX = 31
private const val PRIVATE_192 = 192
private const val PRIVATE_192_SECOND = 168
private const val LINK_LOCAL_169 = 169
private const val LINK_LOCAL_169_SECOND = 254

// fe80::/10 is link-local; fc00::/7 is unique-local (the IPv6 private range).
private const val IPV6_LINK_LOCAL_MASK = 0xFFC0
private const val IPV6_LINK_LOCAL_PREFIX = 0xFE80
private const val IPV6_UNIQUE_LOCAL_MASK = 0xFE00
private const val IPV6_UNIQUE_LOCAL_PREFIX = 0xFC00

/**
 * True when [host] names an address on the user's own network — the addresses Android 17's
 * `ACCESS_LOCAL_NETWORK` gate stands in front of.
 *
 * Covers the private IPv4 ranges (10/8, 172.16/12, 192.168/16), IPv4 link-local (169.254/16),
 * IPv6 link-local (fe80::/10) and unique-local (fc00::/7), and mDNS names (`*.local`, with or
 * without the trailing root dot). Brackets and an IPv6 zone id (`%en0`) are tolerated, as is an
 * IPv4-mapped IPv6 address.
 *
 * Deliberately **not** local:
 * - loopback (`127.0.0.0/8`, `::1`, `localhost`) — the device itself, never gated;
 * - CGNAT `100.64.0.0/10` — Tailscale addresses its peers from it over a VPN interface, which the
 *   gate does not cover. Calling those local would tell a Tailscale user their permission is the
 *   problem when it cannot be.
 *
 * A plain DNS name (`nas.lan`) is not classified here — only its resolved addresses can say.
 */
internal fun isLocalNetworkHost(host: String): Boolean {
    val bare =
        host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
    if (bare.isEmpty()) return false
    if (bare.lowercase().removeSuffix(".").endsWith(MDNS_SUFFIX)) return true
    if (bare.startsWith(IPV4_MAPPED_PREFIX, ignoreCase = true)) {
        return isLocalIpv4(bare.substring(IPV4_MAPPED_PREFIX.length))
    }
    return if (':' in bare) isLocalIpv6(bare) else isLocalIpv4(bare)
}

private fun isLocalIpv4(address: String): Boolean {
    val octets = address.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != IPV4_OCTETS || octets.any { it !in 0..OCTET_MAX }) return false
    val (first, second) = octets
    return first == PRIVATE_10 ||
        (first == PRIVATE_172 && second in PRIVATE_172_SECOND_MIN..PRIVATE_172_SECOND_MAX) ||
        (first == PRIVATE_192 && second == PRIVATE_192_SECOND) ||
        (first == LINK_LOCAL_169 && second == LINK_LOCAL_169_SECOND)
}

private fun isLocalIpv6(address: String): Boolean {
    // Only the first hextet decides both prefixes. "::…" starts with an all-zero hextet.
    val hextet = address.substringBefore(':').ifEmpty { "0" }
    if (hextet.length > HEXTET_MAX_DIGITS) return false
    val firstHextet = hextet.toIntOrNull(HEXTET_RADIX) ?: return false
    return firstHextet and IPV6_LINK_LOCAL_MASK == IPV6_LINK_LOCAL_PREFIX ||
        firstHextet and IPV6_UNIQUE_LOCAL_MASK == IPV6_UNIQUE_LOCAL_PREFIX
}
