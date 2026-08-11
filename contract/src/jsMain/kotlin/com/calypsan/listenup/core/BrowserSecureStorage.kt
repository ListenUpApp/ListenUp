package com.calypsan.listenup.core

import kotlinx.browser.localStorage

/**
 * Browser credential storage over `window.localStorage`.
 *
 * The decision, made explicitly rather than ported: a browser has no keystore. Every web app
 * that keeps a session holds its tokens in origin-scoped storage (localStorage or a cookie),
 * protected by the same-origin policy and nothing else — an attacker who can run script on this
 * origin has the tokens either way. For a self-hosted, private-network app whose native peers
 * already treat the LAN as the trust boundary, origin-scoped plaintext is the honest baseline,
 * not a downgrade smuggled in quietly. If a hardened variant ever matters, it layers here
 * (e.g. non-extractable WebCrypto wrapping) without touching consumers.
 *
 * Keys are namespaced so [clear] can remove exactly this app's entries — localStorage is shared
 * by the whole origin, and `localStorage.clear()` would take innocent bystanders with it.
 */
class BrowserSecureStorage(
    private val namespace: String = DEFAULT_NAMESPACE,
) : SecureStorage {
    override suspend fun save(
        key: String,
        value: String,
    ) {
        localStorage.setItem(namespaced(key), value)
    }

    override suspend fun read(key: String): String? = localStorage.getItem(namespaced(key))

    override suspend fun delete(key: String) {
        localStorage.removeItem(namespaced(key))
    }

    override suspend fun clear() {
        // Two passes: localStorage re-indexes on removal, so removing while walking indexes
        // silently skips every other key.
        val doomed = mutableListOf<String>()
        for (index in 0 until localStorage.length) {
            val key = localStorage.key(index) ?: continue
            if (key.startsWith("$namespace.")) doomed += key
        }
        doomed.forEach { localStorage.removeItem(it) }
    }

    private fun namespaced(key: String): String = "$namespace.$key"
}

private const val DEFAULT_NAMESPACE = "listenup"
