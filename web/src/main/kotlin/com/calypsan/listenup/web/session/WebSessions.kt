package com.calypsan.listenup.web.session

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.web.security.CSRF_COOKIE
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondRedirect
import io.ktor.util.date.GMTDate

/** Opaque browser-session cookie (HttpOnly, SameSite=Lax). */
const val SESSION_COOKIE: String = "lu_session"

/** What a protected handler gets after [requireWebSession] succeeds. */
data class WebSessionContext(
    val session: WebSession,
    val accessToken: AccessToken,
)

private fun ApplicationCall.isHttps(): Boolean = request.origin.scheme == "https"

/** Set the opaque session cookie (value = the store's cookie id). */
fun ApplicationCall.setSessionCookie(cookieId: String) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = cookieId,
            encoding = CookieEncoding.RAW,
            httpOnly = true,
            secure = isHttps(),
            path = "/",
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

/** Set the double-submit CSRF cookie (HttpOnly; value mirrored into the page's meta tag). */
fun ApplicationCall.setCsrfCookie(token: String) {
    response.cookies.append(
        Cookie(
            name = CSRF_COOKIE,
            value = token,
            encoding = CookieEncoding.RAW,
            httpOnly = true,
            secure = isHttps(),
            path = "/",
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

fun ApplicationCall.expireSessionCookie() {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = "",
            encoding = CookieEncoding.RAW,
            expires = GMTDate.START,
            httpOnly = true,
            secure = isHttps(),
            path = "/",
        ),
    )
}

/**
 * Guard a protected web route. Loads the [WebSession] for the session cookie, ensures a
 * fresh access token (single-flight refresh), and returns the [WebSessionContext]. On any
 * failure it issues a redirect to `/login` and returns `null` — callers do `?: return@get`.
 */
suspend fun ApplicationCall.requireWebSession(
    store: WebSessionStore,
    authenticator: WebSessionAuthenticator,
): WebSessionContext? {
    val cookieId = request.cookies[SESSION_COOKIE]
    if (cookieId == null) {
        respondRedirect("/login")
        return null
    }
    val session = store.get(cookieId)
    if (session == null) {
        expireSessionCookie()
        respondRedirect("/login")
        return null
    }
    val accessToken = authenticator.freshAccessToken(session)
    if (accessToken == null) {
        store.remove(cookieId)
        expireSessionCookie()
        respondRedirect("/login")
        return null
    }
    return WebSessionContext(session, accessToken)
}
