package com.calypsan.listenup.web.design

/**
 * Whether a link that came from outside the app (an imported description, a third-party response) may
 * become an `href` at all.
 *
 * An allowlist, not a denylist: `javascript:` is the obvious one to keep out, but so is anything
 * else a browser might come to treat as executable, and only http(s) is actually wanted here.
 */
internal fun isSafeHref(href: String): Boolean {
    val lower = href.lowercase()
    return lower.startsWith("http://") || lower.startsWith("https://")
}
