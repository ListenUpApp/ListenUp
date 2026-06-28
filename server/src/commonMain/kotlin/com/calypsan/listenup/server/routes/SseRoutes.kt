package com.calypsan.listenup.server.routes

import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent

fun Route.sseRoutes() {
    sse("/sse/ping") {
        send(ServerSentEvent(data = """{"message":"pong"}"""))
        close()
    }
}
