package com.calypsan.listenup.client.data.settings

/**
 * Which server a browser client talks to.
 *
 * On every other platform this is a discovery problem — mDNS, a manual URL, a list to choose
 * from. On web it is answered by construction in the common case: the server sent you this page,
 * so the server is this page's origin. There is nothing to discover and nothing to select.
 *
 * [stored] still wins when present, because a bundle can be served from somewhere other than its
 * server, and "Never Stranded" means that case has to be reachable rather than merely unlikely.
 */
fun seedServerUrlFromOrigin(
    stored: String?,
    origin: String,
): String = stored?.takeIf { it.isNotBlank() } ?: origin
