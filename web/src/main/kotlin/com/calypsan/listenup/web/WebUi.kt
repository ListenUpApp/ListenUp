package com.calypsan.listenup.web

import com.calypsan.listenup.web.routes.shellRoutes
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing

/**
 * Configuration handed to the embedded web UI by `:server` at mount time.
 *
 * @property loopbackBaseUrl Base URL the BFF uses to call the server's own REST API
 *   (e.g. "http://127.0.0.1:8080"). Consumed in Phase 1B by the loopback client.
 */
data class WebUiConfig(
    val loopbackBaseUrl: String,
)

/**
 * Mount the embedded HTMX web UI into the host Ktor application.
 *
 * `:web` depends only on `:contract`; it reaches the domain exclusively through the
 * server's REST API over [WebUiConfig.loopbackBaseUrl]. This is the single integration
 * point `:server` calls from its routing setup.
 */
@Suppress("UnusedParameter") // config consumed in Phase 1B when the loopback client is wired in
fun Application.installWebUi(config: WebUiConfig) {
    routing {
        staticResources("/assets", "web")
        shellRoutes()
    }
}
