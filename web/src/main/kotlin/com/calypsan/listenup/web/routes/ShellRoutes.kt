package com.calypsan.listenup.web.routes

import com.calypsan.listenup.web.html.appShell
import io.ktor.server.html.respondHtml
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.h1
import kotlinx.html.p

/** Landing route — renders the app shell. Authenticated content arrives in Phase 1B/2. */
fun Route.shellRoutes() {
    get("/") {
        call.respondHtml {
            appShell(pageTitle = "ListenUp") {
                h1 { +"ListenUp" }
                p { +"Your library, in the browser." }
            }
        }
    }
}
