package com.calypsan.listenup.client.diagnostics

/**
 * Whether this browser can *persist* the local database, and if not, precisely why.
 *
 * Browser-local storage has a four-deep precondition chain the app cannot otherwise see:
 * OPFS needs `SharedArrayBuffer`, which needs cross-origin isolation, which needs COOP/COEP
 * response headers, which the browser honours only on a trustworthy origin — `localhost` or
 * real HTTPS. Break any link and the application observes exactly one symptom: the database
 * did not open.
 *
 * Reporting a generic failure at the moment the environment is at fault is accurate and
 * useless, so this names the missing link instead.
 *
 * ⛔ **A broken link is no longer fatal.** It used to be: `main` rendered the reason and stopped,
 * so a server on plain `http://` over a LAN — the setup this project actually recommends — served
 * a web client that refused to run. SQLite in memory needs none of this chain, so the app now
 * falls back to it and says what that costs. Persistence is the thing at stake here, not the app.
 */
sealed interface BrowserStoreEnvironment {
    /** Every precondition holds; the store is persistent across visits. */
    data object Ready : BrowserStoreEnvironment

    /**
     * A precondition failed, so the store is in memory only and does not survive a reload.
     *
     * [reason] names the specific missing link, for display. It is not an error: the app runs
     * with every feature, and re-syncs from the server on each visit instead of reading what it
     * kept last time.
     */
    data class Degraded(
        val reason: String,
    ) : BrowserStoreEnvironment
}

/**
 * Checks the persistence preconditions in dependency order, reporting the first that fails —
 * the outermost cause is the actionable one.
 */
fun checkBrowserStoreEnvironment(): BrowserStoreEnvironment {
    if (!js("window.isSecureContext").unsafeCast<Boolean>()) {
        return BrowserStoreEnvironment.Degraded(
            "This page is not a secure context. The local database needs HTTPS or localhost.",
        )
    }

    if (!js("window.crossOriginIsolated").unsafeCast<Boolean>()) {
        return BrowserStoreEnvironment.Degraded(
            "This page is not cross-origin isolated. The server must send the " +
                "Cross-Origin-Opener-Policy and Cross-Origin-Embedder-Policy headers.",
        )
    }

    if (js("typeof SharedArrayBuffer").toString() != "function") {
        return BrowserStoreEnvironment.Degraded(
            "SharedArrayBuffer is unavailable, so SQLite cannot use origin-private storage.",
        )
    }

    val hasStorage = js("typeof navigator.storage !== 'undefined'").unsafeCast<Boolean>()
    val hasGetDir = hasStorage && js("typeof navigator.storage.getDirectory === 'function'").unsafeCast<Boolean>()
    if (!hasGetDir) {
        return BrowserStoreEnvironment.Degraded(
            "This browser does not support the origin private file system (OPFS).",
        )
    }

    return BrowserStoreEnvironment.Ready
}
