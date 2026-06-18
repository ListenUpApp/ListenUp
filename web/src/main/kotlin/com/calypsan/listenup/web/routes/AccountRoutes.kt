package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.respondPage
import com.calypsan.listenup.web.html.sessionsErrorFragment
import com.calypsan.listenup.web.html.sessionsList
import com.calypsan.listenup.web.html.sessionsListFragment
import com.calypsan.listenup.web.security.newCsrfToken
import com.calypsan.listenup.web.security.webCsrfConfig
import com.calypsan.listenup.web.session.SESSION_COOKIE
import com.calypsan.listenup.web.session.expireSessionCookie
import com.calypsan.listenup.web.session.requireWebSession
import com.calypsan.listenup.web.session.setCsrfCookie
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.accountRoutes(deps: WebDependencies) {
    route("/logout") {
        install(CSRF, webCsrfConfig)
        post {
            val ctx = call.requireWebSession(deps.store, deps.authenticator) ?: return@post
            deps.loopback.logout(ctx.accessToken)
            call.request.cookies[SESSION_COOKIE]?.let { deps.store.remove(it) }
            call.expireSessionCookie()
            call.response.header("HX-Redirect", "/login")
            call.respondText("", ContentType.Text.Html)
        }
    }

    route("/account/sessions") {
        install(CSRF, webCsrfConfig)
        get {
            val ctx = call.requireWebSession(deps.store, deps.authenticator) ?: return@get
            when (val result = deps.loopback.listSessions(ctx.accessToken)) {
                is AppResult.Success -> {
                    val token = newCsrfToken()
                    call.setCsrfCookie(token)
                    call.respondPage(title = "Sessions", csrfToken = token) { sessionsList(result.data) }
                }
                is AppResult.Failure -> {
                    call.respondText(sessionsErrorFragment(result.error.message), ContentType.Text.Html)
                }
            }
        }
        delete("/{id}") {
            val ctx = call.requireWebSession(deps.store, deps.authenticator) ?: return@delete
            val id = SessionId(call.parameters["id"].orEmpty())
            deps.loopback.revokeSession(ctx.accessToken, id)
            when (val refreshed = deps.loopback.listSessions(ctx.accessToken)) {
                is AppResult.Success ->
                    call.respondText(sessionsListFragment(refreshed.data), ContentType.Text.Html)
                is AppResult.Failure ->
                    call.respondText("", ContentType.Text.Html)
            }
        }
    }
}
