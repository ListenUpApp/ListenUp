package com.calypsan.listenup.server.metadata

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import io.ktor.http.Url

/**
 * Validates that an image URL is safe for the server to fetch: HTTPS, and hosted at a public-
 * unicast destination rather than loopback, link-local, or private address space.
 *
 * Guards [com.calypsan.listenup.server.api.MetadataLookupServiceImpl.applyCover] — the one RPC
 * method that accepts a caller-supplied URL — and every hop of [ImageStorage.downloadBytes]'s
 * redirect chain, so a public host that 302s to an internal one is rejected exactly like a direct
 * request to that internal host would be.
 *
 * This is a string-level check, not a DNS-resolving one: it classifies hosts that are themselves
 * literal loopback/link-local/private addresses (or `localhost`), without resolving arbitrary
 * hostnames to IPs. That keeps the check pure, fast, and multiplatform (no `java.net.InetAddress`
 * — this file compiles for both the JVM and Kotlin/Native targets). A DNS-rebinding attacker who
 * points a public-looking hostname at a private IP is out of scope for this check alone; the
 * redirect re-validation closes the far more common bypass, where the initial URL is honest and a
 * later hop is not.
 */
object SafeCoverUrl {
    private const val REJECTION_REASON = "cover URL rejected: must be a public HTTPS destination"

    /** Returns `null` when [url] is safe to fetch, or a typed [AppError] describing the rejection. */
    fun validate(url: String): AppError? {
        val parsed = runCatching { Url(url) }.getOrNull() ?: return unsafe()
        if (!parsed.protocol.name.equals("https", ignoreCase = true)) return unsafe()
        val host = parsed.host
        if (host.isBlank() || isUnsafeHost(host)) return unsafe()
        return null
    }

    private fun unsafe(): AppError = MetadataError.UnsafeUrl(debugInfo = REJECTION_REASON)

    private fun isUnsafeHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        parseIPv4(normalized)?.let { return isReservedIPv4(it) }
        if (normalized.contains(':')) return !isGlobalUnicastIPv6(normalized)
        // An ordinary DNS hostname. Resolving it to check for DNS-rebinding is out of scope (see
        // class doc) — the redirect-hop re-validation is what catches a bait-and-switch host.
        return false
    }

    // ─── IPv4 ─────────────────────────────────────────────────────────────────

    /** Parses a dotted-quad IPv4 literal into a 32-bit address, or `null` if [host] isn't one. */
    private fun parseIPv4(host: String): UInt? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        var addr = 0u
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val octet = part.toUIntOrNull() ?: return null
            if (octet > MAX_IPV4_OCTET) return null
            addr = (addr shl BITS_PER_OCTET) or octet
        }
        return addr
    }

    private data class Cidr4(
        val network: UInt,
        val prefixBits: Int,
    )

    /**
     * IPv4 blocks that are never a legitimate public cover host: loopback, link-local (including
     * the 169.254.169.254 cloud-metadata endpoint), RFC 1918 private space, CGNAT, IETF/
     * documentation/benchmarking reservations, and multicast/reserved/broadcast (224.0.0.0-
     * 255.255.255.255).
     */
    private val RESERVED_IPV4_BLOCKS =
        listOf(
            Cidr4(parseIPv4("0.0.0.0")!!, 8),
            Cidr4(parseIPv4("10.0.0.0")!!, 8),
            Cidr4(parseIPv4("100.64.0.0")!!, 10),
            Cidr4(parseIPv4("127.0.0.0")!!, 8),
            Cidr4(parseIPv4("169.254.0.0")!!, 16),
            Cidr4(parseIPv4("172.16.0.0")!!, 12),
            Cidr4(parseIPv4("192.0.0.0")!!, 24),
            Cidr4(parseIPv4("192.0.2.0")!!, 24),
            Cidr4(parseIPv4("192.168.0.0")!!, 16),
            Cidr4(parseIPv4("198.18.0.0")!!, 15),
            Cidr4(parseIPv4("198.51.100.0")!!, 24),
            Cidr4(parseIPv4("203.0.113.0")!!, 24),
            Cidr4(parseIPv4("224.0.0.0")!!, 4),
            Cidr4(parseIPv4("240.0.0.0")!!, 4),
        )

    private fun isReservedIPv4(addr: UInt): Boolean =
        RESERVED_IPV4_BLOCKS.any { (network, bits) ->
            val mask = if (bits == 0) 0u else 0xFFFFFFFFu shl UInt.SIZE_BITS - bits
            addr and mask == network and mask
        }

    // ─── IPv6 ─────────────────────────────────────────────────────────────────

    /**
     * Accepts only global-unicast IPv6 (`2000::/3`) — the complement of loopback (`::1`),
     * unspecified (`::`), unique-local (`fc00::/7`), link-local (`fe80::/10`), multicast
     * (`ff00::/8`), and IPv4-mapped/compatible forms (`::ffff:0:0/96`, all under `0000::/8`), all
     * of which fall outside `2000::/3` and are rejected by this single range check.
     */
    private fun isGlobalUnicastIPv6(host: String): Boolean {
        val firstGroup = host.removePrefix("[").substringBefore(':').ifEmpty { "0" }
        val value = firstGroup.toIntOrNull(HEX_RADIX) ?: return false
        return value in GLOBAL_UNICAST_IPV6_LOW..GLOBAL_UNICAST_IPV6_HIGH
    }

    private const val BITS_PER_OCTET = 8
    private const val MAX_IPV4_OCTET = 255u
    private const val HEX_RADIX = 16
    private const val GLOBAL_UNICAST_IPV6_LOW = 0x2000
    private const val GLOBAL_UNICAST_IPV6_HIGH = 0x3FFF
}

/**
 * Thrown by [ImageStorage] when [SafeCoverUrl] rejects the initial URL or a redirect hop.
 * Carries the typed [appError] so callers can surface it directly as an `AppResult.Failure`
 * instead of falling through to a generic network-failure mapping.
 */
class UnsafeCoverUrlException(
    val appError: AppError,
) : Exception(appError.code)
