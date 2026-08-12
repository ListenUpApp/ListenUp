package com.calypsan.listenup.client.core

import com.calypsan.listenup.core.SecureStorage
import kotlinx.browser.document
import kotlinx.browser.window

/**
 * Mirrors the access token into a cookie so the DOM can authenticate on our behalf.
 *
 * An `<img src>` cannot carry an `Authorization` header, so a cover request has no way to present
 * the token the RPC channel uses. The server accepts a cookie on its blob-read provider for exactly
 * this (`JwtAuth.kt`'s `ACCESS_TOKEN_COOKIE` — **the two names must agree**, and nothing checks
 * that they do; a rename on either side shows up as every image on the page silently failing to
 * load, which an `<img>` reports as nothing at all).
 *
 * **Not HttpOnly, and that is not a downgrade.** The token already lives in origin-scoped
 * `localStorage` (`BrowserSecureStorage`), so a script running on this origin can read it either
 * way. Making the cookie HttpOnly would require a server round-trip to mint it while removing no
 * real exposure.
 *
 * `SameSite=Strict` is defence-in-depth rather than the thing the design rests on — the server
 * bounds the cookie's reach by mounting it on byte-serving GETs only. It still closes a real
 * residual: with the cookie attached to `<img>` requests from any origin, a hostile page could
 * distinguish 200 from 404 via `onload`/`onerror` and enumerate which books a visitor can reach —
 * the exact existence leak the server answers 404 to avoid. Strict rather than Lax because this
 * cookie is only ever needed for same-site subresource loads after the app has booted; the shell
 * itself is not cookie-gated, so nothing needs it on a cross-site top-level navigation.
 *
 * Only the access token is mirrored. Every other key — refresh tokens most of all — stays out of
 * the DOM's reach, because nothing in the DOM needs to present one.
 */
class CookieMirroringSecureStorage(
    private val delegate: SecureStorage,
) : SecureStorage {
    override suspend fun save(
        key: String,
        value: String,
    ) {
        delegate.save(key, value)
        if (key == KEY_ACCESS_TOKEN) writeCookie(value)
    }

    override suspend fun read(key: String): String? = delegate.read(key)

    override suspend fun delete(key: String) {
        delegate.delete(key)
        if (key == KEY_ACCESS_TOKEN) clearCookie()
    }

    override suspend fun clear() {
        delegate.clear()
        clearCookie()
    }

    /**
     * Rewritten on every token write, not just the first.
     *
     * The token is short-lived (≤15m) and the refresh path saves a new one through this same seam,
     * so mirroring every save is what stops covers 401-ing mid-session once the original expires.
     */
    private fun writeCookie(token: String) {
        document.cookie = "$ACCESS_TOKEN_COOKIE=$token; path=/; SameSite=Strict${secureFlag()}"
    }

    private fun clearCookie() {
        document.cookie = "$ACCESS_TOKEN_COOKIE=; path=/; Max-Age=0; SameSite=Strict${secureFlag()}"
    }

    /**
     * `Secure` is omitted on plain HTTP, or the browser drops the write silently — a self-hosted
     * server on a LAN is routinely reached over `http://`, and a cookie that is never set produces
     * broken covers with nothing logged anywhere.
     */
    private fun secureFlag(): String = if (window.location.protocol == "https:") "; Secure" else ""

    private companion object {
        /** Must match `AuthSessionStore.KEY_ACCESS_TOKEN`. */
        const val KEY_ACCESS_TOKEN = "access_token"

        /** Must match the server's `ACCESS_TOKEN_COOKIE` in `JwtAuth.kt`. */
        const val ACCESS_TOKEN_COOKIE = "listenup_access"
    }
}
