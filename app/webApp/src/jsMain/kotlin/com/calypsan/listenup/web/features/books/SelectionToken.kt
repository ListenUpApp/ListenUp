package com.calypsan.listenup.web.features.books

import kotlinx.browser.window
import kotlin.random.Random

/**
 * A selection too large for a URL, parked in this tab's `sessionStorage` under a short token.
 *
 * `sessionStorage` survives a reload of the tab but not a bookmark or a new tab, which is exactly the
 * promise the bulk editor's URL makes for a large selection. Every access is guarded: a blocked or full
 * storage must not white-page the shell, it just means the selection cannot be kept.
 */
internal object SelectionToken {
    private const val KEY_PREFIX = "listenup.selection."
    private const val TOKEN_LENGTH = 8
    private const val BASE_36 = 36
    private const val ID_SEPARATOR = ","

    /** Stores [ids] and returns the token that reads them back, or null when the browser refused to keep them. */
    fun store(ids: Collection<String>): String? =
        try {
            val token = newToken()
            window.sessionStorage.setItem(KEY_PREFIX + token, ids.joinToString(ID_SEPARATOR))
            token
        } catch (ignored: Throwable) {
            // Blocked or full storage (private modes, quota) must not white-page; the caller falls back to the URL.
            null
        }

    /** The ids [token] names in this tab, in the order they were stored; null when the entry is gone or unreadable. */
    fun read(token: String): List<String>? =
        try {
            window.sessionStorage
                .getItem(KEY_PREFIX + token)
                ?.split(ID_SEPARATOR)
                ?.filter { it.isNotBlank() }
        } catch (ignored: Throwable) {
            // Same reason as in store: unreadable storage is an absent selection, not a crash.
            null
        }

    private fun newToken(): String =
        buildString {
            repeat(TOKEN_LENGTH) { append(Random.nextInt(BASE_36).toString(BASE_36)) }
        }
}
