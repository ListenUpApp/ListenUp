package com.calypsan.listenup.web.routes

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.appShell
import com.calypsan.listenup.web.session.SESSION_COOKIE
import io.ktor.server.application.call
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.h1
import kotlinx.html.p

internal fun Route.entryRoutes(deps: WebDependencies) {
    get("/") {
        when (val info = deps.loopback.serverInfo()) {
            is AppResult.Success -> {
                if (info.data.setupRequired) {
                    call.respondRedirect("/setup")
                    return@get
                }
                // Placeholder home: presence-only session check (renders static text, makes
                // no authenticated loopback call). When this becomes a real authenticated
                // shell, switch to `call.requireWebSession(deps.store, deps.authenticator)` so
                // the access token is refreshed and dead/expired sessions bounce to /login.
                val cookieId = call.request.cookies[SESSION_COOKIE]
                val session = cookieId?.let { deps.store.get(it) }
                if (session == null) {
                    call.respondRedirect("/login")
                    return@get
                }
                call.respondHtml {
                    appShell(pageTitle = "ListenUp") {
                        h1 { +"ListenUp" }
                        p { +"Your library, in the browser." }
                    }
                }
            }
            is AppResult.Failure -> call.respondRedirect("/login")
        }
    }
}

/** Register + pending. Filled in Task 6. */
internal fun Route.registerRoutes(deps: WebDependencies) {
    // Task 6
}

/** Logout + active sessions. Filled in Task 7. */
internal fun Route.accountRoutes(deps: WebDependencies) {
    // Task 7
}
