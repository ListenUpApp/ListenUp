package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.session.WebSession
import com.calypsan.listenup.web.session.setSessionCookie
import io.ktor.server.application.ApplicationCall

/** Create a server-side web session for [authSession] and set the browser session cookie. */
internal fun startWebSession(
    deps: WebDependencies,
    call: ApplicationCall,
    authSession: AuthSession,
) {
    val cookieId = deps.store.newCookieId()
    deps.store.put(
        cookieId,
        WebSession(
            sessionId = authSession.sessionId,
            userId = authSession.user.id,
            role = authSession.user.role,
            accessToken = authSession.accessToken,
            refreshToken = authSession.refreshToken,
            accessExpiresAt = authSession.accessTokenExpiresAt,
        ),
    )
    call.setSessionCookie(cookieId)
}
