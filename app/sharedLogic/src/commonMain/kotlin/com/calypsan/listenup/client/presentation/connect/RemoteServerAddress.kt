package com.calypsan.listenup.client.presentation.connect

import com.calypsan.listenup.client.data.repository.isLocalNetworkHost

private const val IPV4_OCTETS = 4
private const val OCTET_MAX = 255
private const val MIN_TLD_LENGTH = 2

/**
 * Suffixes people give names on their own network. A name ending in one might resolve to the LAN,
 * so it never counts as clearly remote.
 */
private val PRIVATE_NAME_SUFFIXES =
    setOf("lan", "home", "arpa", "internal", "intranet", "localdomain", "corp", "private", "localhost")

/**
 * True when [typedUrl] is clearly a server off the user's local network: a complete public IP
 * address, or a dotted domain name with a public-looking top-level label.
 *
 * The manual-entry screen uses this to set aside its local-network permission card while the
 * user types a remote server, since the permission can't be what stops that connection. It is
 * deliberately cautious and synchronous — no DNS for a UI decision. Anything that *might* be local
 * answers false and keeps the card: an empty or half-typed address, anything unparseable, a LAN or
 * `.local` address, and a bare or private-suffix name such as `nas` or `nas.lan`, which may well
 * resolve to the LAN.
 *
 * Tailscale's CGNAT addresses (`100.64.0.0/10`) count as remote: they travel over a VPN interface
 * that the permission never covers.
 */
fun isClearlyRemoteServerAddress(typedUrl: String): Boolean {
    val typed = typedUrl.trim()
    if (typed.isEmpty()) return false
    val host = connectTarget(typed)?.run { host.trimEnd('.').lowercase() } ?: return false
    if (host.isEmpty() || isLocalNetworkHost(host)) return false
    return when {
        // A complete IPv6 literal (it parsed) that isn't link- or unique-local.
        ':' in host -> true

        host.all { it.isDigit() || it == '.' } -> isCompleteIpv4(host)

        else -> isPublicDomainName(host)
    }
}

private fun isCompleteIpv4(host: String): Boolean {
    val octets = host.split('.')
    return octets.size == IPV4_OCTETS && octets.all { octet -> octet.toIntOrNull()?.let { it in 0..OCTET_MAX } == true }
}

private fun isPublicDomainName(host: String): Boolean {
    val labels = host.split('.')
    val topLevel = labels.last()
    return labels.size >= 2 &&
        labels.none { it.isEmpty() } &&
        topLevel.length >= MIN_TLD_LENGTH &&
        topLevel.all { it.isLetter() } &&
        topLevel !in PRIVATE_NAME_SUFFIXES
}
