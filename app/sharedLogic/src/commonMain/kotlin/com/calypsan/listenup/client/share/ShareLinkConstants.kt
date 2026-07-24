package com.calypsan.listenup.client.share

/**
 * Compile-time constants for ListenUp share links.
 *
 * The share domain is **developer-owned** infrastructure — a tiny static redirector that
 * lets a tapped link open the app or fall back to the install page. It is never a
 * self-hosted server's address, and (because the payload rides in the URL fragment) it
 * never learns what was shared.
 */
object ShareLinkConstants {
    /** Host of the static share-link redirector. App/Universal Links verify against this domain. */
    const val SHARE_DOMAIN: String = "link.listenup.audio"

    /** The single generalized "open" path. Book ships first; series/contributors reuse it later. */
    const val OPEN_PATH: String = "/o"

    /** The `https` prefix every shareable link starts with: `https://link.listenup.audio/o`. */
    const val SHARE_URL_PREFIX: String = "https://$SHARE_DOMAIN$OPEN_PATH"
}
