package com.calypsan.listenup.web.testing

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.web.WebDependencies
import com.calypsan.listenup.web.installWebUi
import com.calypsan.listenup.web.security.CSRF_HEADER
import com.calypsan.listenup.web.session.WebSessionAuthenticator
import com.calypsan.listenup.web.session.WebSessionStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder

/**
 * Mounts the web routes in an isolated in-memory app with the supplied [fake] loopback
 * client + a shared [store]. Tests get a cookie-aware, redirect-suppressing client so they
 * can assert on `Set-Cookie`, `Location`, and `HX-Redirect` directly.
 */
internal data class WebTestContext(
    val fake: FakeLoopbackAuthClient,
    val store: WebSessionStore,
)

internal fun ApplicationTestBuilder.installTestWebUi(
    fake: FakeLoopbackAuthClient = FakeLoopbackAuthClient(),
    store: WebSessionStore = WebSessionStore(),
): WebTestContext {
    application {
        installWebUi(
            WebDependencies(
                loopback = fake,
                store = store,
                authenticator = WebSessionAuthenticator(fake, clock = { 0L }),
            ),
        )
    }
    return WebTestContext(fake, store)
}

internal fun ApplicationTestBuilder.webClient(): HttpClient =
    createClient {
        install(ContentNegotiation) { json(contractJson) }
        install(HttpCookies)
        followRedirects = false
    }

/** Attach the CSRF header for a mutating request. */
internal fun HttpRequestBuilder.csrf(token: String) {
    header(CSRF_HEADER, token)
}

/** Read the current `lu_csrf` value from the cookie-aware client's jar (set on the form GET). */
internal suspend fun HttpClient.csrfToken(): String =
    cookies("http://localhost/").first { it.name == "lu_csrf" }.value
