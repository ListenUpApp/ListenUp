package com.calypsan.listenup.web.session

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory `cookieId → WebSession` registry. Cleared on restart (web users re-login —
 * accepted per the self-host threat model). Cookie ids are 256-bit, URL-safe, unguessable.
 */
class WebSessionStore {
    private val sessions = ConcurrentHashMap<String, WebSession>()
    private val random = SecureRandom()

    fun get(cookieId: String): WebSession? = sessions[cookieId]

    fun put(cookieId: String, session: WebSession) {
        sessions[cookieId] = session
    }

    fun remove(cookieId: String): WebSession? = sessions.remove(cookieId)

    fun newCookieId(): String {
        val bytes = ByteArray(COOKIE_ID_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        const val COOKIE_ID_BYTES = 32
    }
}
