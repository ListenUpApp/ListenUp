package com.calypsan.listenup.web

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.web.loopback.KtorLoopbackAuthClient
import com.calypsan.listenup.web.loopback.LoopbackAuthClient
import com.calypsan.listenup.web.routes.accountRoutes
import com.calypsan.listenup.web.routes.entryRoutes
import com.calypsan.listenup.web.routes.loginRoutes
import com.calypsan.listenup.web.routes.registerRoutes
import com.calypsan.listenup.web.session.WebSessionAuthenticator
import com.calypsan.listenup.web.session.WebSessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.routing

/**
 * Configuration handed to the embedded web UI by `:server` at mount time.
 *
 * @property loopbackBaseUrl Base URL the BFF uses to call the server's own REST API
 *   (e.g. "http://127.0.0.1:8080").
 */
data class WebUiConfig(
    val loopbackBaseUrl: String,
)

/** Collaborators the web routes need. Injected so tests can supply a fake loopback client. */
internal data class WebDependencies(
    val loopback: LoopbackAuthClient,
    val store: WebSessionStore,
    val authenticator: WebSessionAuthenticator,
)

/**
 * Production entry point: builds the real loopback HTTP client (CIO, pointed at
 * [WebUiConfig.loopbackBaseUrl], using [contractJson]) plus an in-memory session store, and
 * mounts the web UI. `:server` calls this from its routing setup.
 */
fun Application.installWebUi(config: WebUiConfig) {
    val httpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(contractJson) }
            defaultRequest { url(config.loopbackBaseUrl) }
        }
    val loopback = KtorLoopbackAuthClient(httpClient)
    val deps =
        WebDependencies(
            loopback = loopback,
            store = WebSessionStore(),
            authenticator = WebSessionAuthenticator(loopback),
        )
    installWebUi(deps)
}

/** Internal seam: mount the routes against supplied [deps] (tests inject a fake loopback). */
internal fun Application.installWebUi(deps: WebDependencies) {
    routing {
        staticResources("/assets", "web")
        entryRoutes(deps)
        loginRoutes(deps)
        registerRoutes(deps)
        accountRoutes(deps)
    }
}
