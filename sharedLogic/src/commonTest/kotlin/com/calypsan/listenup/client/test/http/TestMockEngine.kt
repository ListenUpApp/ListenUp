package com.calypsan.listenup.client.test.http

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * Builds a [MockEngine] for seam-level HTTP tests, dispatching by `request.url.encodedPath`.
 *
 * Unknown paths fail the test with an [AssertionError] — tests are explicit about every
 * endpoint they touch, so a drift-in typo is caught immediately instead of silently
 * falling through to a default response.
 *
 * ```
 * val client = HttpClient(testMockEngine {
 *     respondJson("/api/v1/books") { """{"id":"book-1"}""" }
 *     respondStatus("/api/v1/missing", HttpStatusCode.NotFound)
 * })
 * ```
 *
 * Source: Ktor client testing guide — https://ktor.io/docs/client-testing.html.
 * See Finding 12 D3 for the motivation (zero MockEngine usage at audit time).
 */
fun testMockEngine(configure: TestMockEngineBuilder.() -> Unit): MockEngine {
    val builder = TestMockEngineBuilder()
    builder.configure()
    return MockEngine { request ->
        val path = request.url.encodedPath
        val handler =
            builder.handlers[path]
                ?: throw AssertionError(
                    "TestMockEngine: no handler for path '$path'. Registered paths: ${builder.handlers.keys}",
                )
        handler(request)
    }
}

class TestMockEngineBuilder internal constructor() {
    internal val handlers: MutableMap<
        String,
        suspend MockRequestHandleScope.(
            HttpRequestData,
        ) -> HttpResponseData,
    > = mutableMapOf()

    /**
     * Responds to [path] with an `application/json` body supplied by [body]. Status defaults
     * to 200 OK; pass [status] to override.
     */
    fun respondJson(
        path: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: () -> String,
    ) {
        handlers[path] = {
            respond(
                content = body(),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    }

    /** Responds to [path] with [status] and an empty body. Useful for testing error paths. */
    fun respondStatus(
        path: String,
        status: HttpStatusCode,
    ) {
        handlers[path] = { respondError(status) }
    }

    /**
     * Registers a custom handler for [path]. Use when the response depends on request state
     * (attempt counter, headers, etc.) or when you want fine-grained control over body and
     * headers beyond [respondJson] / [respondStatus].
     */
    fun handle(
        path: String,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        handlers[path] = handler
    }
}
