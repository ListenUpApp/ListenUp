@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.deeplink

import android.content.Intent
import android.net.Uri
import com.calypsan.listenup.client.data.repository.InviteDeepLink

/**
 * Parsed book deep link: listenup://book/{bookId}
 */
data class BookDeepLink(
    val bookId: String,
)

/**
 * Parses deep link intents into structured data.
 *
 * Handles custom scheme deep links:
 * listenup://join?server=https://audiobooks.example.com&code=ABC123
 */
object DeepLinkParser {
    private const val CUSTOM_SCHEME = "listenup"
    private const val JOIN_HOST = "join"
    private const val BOOK_HOST = "book"

    /**
     * Parses an intent into an InviteDeepLink if it contains a valid invite URL.
     *
     * @param intent The incoming intent (from onCreate or onNewIntent)
     * @return Parsed invite data, or null if not a valid invite deep link
     */
    fun parse(intent: Intent?): InviteDeepLink? {
        if (intent?.action != Intent.ACTION_VIEW) return null

        val uri = intent.data ?: return null
        return parseUri(uri)
    }

    /**
     * Parses a book deep link from an intent.
     * Handles: listenup://book/{bookId}
     */
    fun parseBookDeepLink(intent: Intent?): BookDeepLink? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        if (uri.scheme?.lowercase() != CUSTOM_SCHEME) return null
        if (uri.host?.lowercase() != BOOK_HOST) return null
        val bookId = uri.pathSegments?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return BookDeepLink(bookId)
    }

    /**
     * Parses a URI into an InviteDeepLink.
     *
     * Handles: listenup://join?server=URL&code=CODE
     *
     * @param uri The deep link URI
     * @return Parsed invite data, or null if not a valid invite URL
     */
    fun parseUri(uri: Uri): InviteDeepLink? =
        if (uri.scheme?.lowercase() == CUSTOM_SCHEME) parseCustomScheme(uri) else null

    /**
     * Parses custom scheme: listenup://join?server=URL&code=CODE
     */
    private fun parseCustomScheme(uri: Uri): InviteDeepLink? {
        if (uri.host?.lowercase() != JOIN_HOST) return null

        val serverUrl = uri.getQueryParameter("server") ?: return null
        val code = uri.getQueryParameter("code") ?: return null

        if (serverUrl.isBlank() || code.isBlank()) return null

        return InviteDeepLink(
            serverUrl = serverUrl,
            code = code,
        )
    }
}
