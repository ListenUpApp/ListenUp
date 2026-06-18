package com.calypsan.listenup.web.routes

import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.html.appShell
import io.ktor.server.html.respondHtml
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.p

/** Entry routing. Filled in Task 4. */
internal fun Route.entryRoutes(deps: WebDependencies) {
    // Placeholder: Task 4 replaces this with the real authenticated library shell.
    get("/") {
        call.respondHtml {
            appShell(pageTitle = "ListenUp") {
                p { +"ListenUp" }
            }
        }
    }
}

/** Login + first-run setup. Filled in Task 4/5. */
internal fun Route.loginRoutes(deps: WebDependencies) {
    // Task 4 (login), Task 5 (setup)
}

/** Register + pending. Filled in Task 6. */
internal fun Route.registerRoutes(deps: WebDependencies) {
    // Task 6
}

/** Logout + active sessions. Filled in Task 7. */
internal fun Route.accountRoutes(deps: WebDependencies) {
    // Task 7
}
