package com.calypsan.listenup.client.data.repository

/**
 * Host part of an absolute `http(s)` URL — the identity that matters for "is this the same server".
 *
 * Scheme, port and path are deliberately dropped: a port change or an http→https upgrade of the
 * same host is still the same server, and the credentials it issued stay valid there. A different
 * host is a different server, whatever else matches. Used by [SettingsRepositoryImpl.setServerUrl]
 * to decide whether the persisted session must be cleared, and by the invite claim flow to decide
 * whether a link-supplied address needs the user's confirmation before it is persisted.
 *
 * The port is dropped by cutting at the first `:` of the authority — except for an IPv6 literal,
 * which is bracketed (`[::1]:8080`) and full of colons, so there the host is the bracketed literal
 * up to and including `]`; splitting on `:` would collapse every IPv6 server to `[` and make two
 * different servers compare equal. The result is lower-cased because hostnames are case-insensitive:
 * `Example.com` and `example.com` are the same server and must not trigger a spurious sign-out.
 */
internal fun String.hostOfUrl(): String {
    val authority = substringAfter("://").substringBefore('/')
    val host =
        if (authority.startsWith('[')) {
            val end = authority.indexOf(']')
            if (end >= 0) authority.substring(0, end + 1) else authority
        } else {
            authority.substringBefore(':')
        }
    return host.lowercase()
}
