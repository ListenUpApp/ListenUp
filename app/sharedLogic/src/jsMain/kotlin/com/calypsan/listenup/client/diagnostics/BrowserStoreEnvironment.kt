package com.calypsan.listenup.client.diagnostics

/**
 * Whether this browser can host the local database, and if not, precisely why.
 *
 * Browser-local storage has a four-deep precondition chain the app cannot otherwise see:
 * OPFS needs `SharedArrayBuffer`, which needs cross-origin isolation, which needs COOP/COEP
 * response headers, which the browser honours only on a trustworthy origin — `localhost` or
 * real HTTPS. Break any link and the application observes exactly one symptom: the database
 * did not open.
 *
 * Reporting a generic failure at the moment the environment is at fault is accurate and
 * useless, so this names the missing link instead.
 */
sealed interface BrowserStoreEnvironment {
    /** Every precondition holds; the store can be opened. */
    data object Ready : BrowserStoreEnvironment

    /** A precondition failed. [reason] names the specific missing link, for display. */
    data class Unavailable(
        val reason: String,
    ) : BrowserStoreEnvironment
}

/**
 * Checks the storage preconditions in dependency order, reporting the first that fails —
 * the outermost cause is the actionable one.
 */
fun checkBrowserStoreEnvironment(): BrowserStoreEnvironment {
    if (!js("window.isSecureContext").unsafeCast<Boolean>()) {
        return BrowserStoreEnvironment.Unavailable(
            "This page is not a secure context. The local database needs HTTPS or localhost.",
        )
    }

    if (!js("window.crossOriginIsolated").unsafeCast<Boolean>()) {
        return BrowserStoreEnvironment.Unavailable(
            "This page is not cross-origin isolated. The server must send the " +
                "Cross-Origin-Opener-Policy and Cross-Origin-Embedder-Policy headers.",
        )
    }

    if (js("typeof SharedArrayBuffer").toString() != "function") {
        return BrowserStoreEnvironment.Unavailable(
            "SharedArrayBuffer is unavailable, so SQLite cannot use origin-private storage.",
        )
    }

    val hasStorage = js("typeof navigator.storage !== 'undefined'").unsafeCast<Boolean>()
    val hasGetDir = hasStorage && js("typeof navigator.storage.getDirectory === 'function'").unsafeCast<Boolean>()
    if (!hasGetDir) {
        return BrowserStoreEnvironment.Unavailable(
            "This browser does not support the origin private file system (OPFS).",
        )
    }

    return BrowserStoreEnvironment.Ready
}
