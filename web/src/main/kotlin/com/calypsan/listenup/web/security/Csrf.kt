package com.calypsan.listenup.web.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.csrf.CSRFConfig
import io.ktor.server.response.respond
import java.security.SecureRandom
import java.util.Base64

/** Name of the double-submit CSRF cookie (HttpOnly; the page carries the same value in a meta tag). */
const val CSRF_COOKIE: String = "lu_csrf"

/** Header htmx echoes the token back in (see the app-shell `configRequest` hook). */
const val CSRF_HEADER: String = "X-CSRF-Token"

private val csrfRandom = SecureRandom()
private const val CSRF_TOKEN_BYTES = 32

/** Mint a fresh, unguessable CSRF token (URL-safe base64, no padding). */
fun newCsrfToken(): String {
    val bytes = ByteArray(CSRF_TOKEN_BYTES)
    csrfRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * The shared CSRF config for every mutating web route. Double-submit: the submitted
 * [CSRF_HEADER] must equal the [CSRF_COOKIE] value set when the page was rendered. The
 * plugin skips GET/HEAD/OPTIONS and fails closed when the header is absent.
 *
 * Note: [CSRFConfig.checkHeader]'s lambda is `ApplicationCall.(headerValue: String) -> Boolean`
 * (Kotlin extension function type — `this` is the call, the param is the submitted header value).
 * [CSRFConfig.onFailure]'s lambda is `suspend ApplicationCall.(reason: String) -> Unit`.
 */
val webCsrfConfig: CSRFConfig.() -> Unit = {
    checkHeader(CSRF_HEADER) { token: String ->
        token.isNotEmpty() && token == request.cookies[CSRF_COOKIE]
    }
    onFailure { _: String ->
        respond(HttpStatusCode.Forbidden)
    }
}
