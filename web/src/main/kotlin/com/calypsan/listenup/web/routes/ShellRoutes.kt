package com.calypsan.listenup.web.routes

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Placeholder landing route. Real app-shell HTML is added in Task 3. */
fun Route.shellRoutes() {
    get("/") {
        call.respondText(
            "<!doctype html><title>ListenUp</title><h1>ListenUp</h1>",
            ContentType.Text.Html,
        )
    }
}
