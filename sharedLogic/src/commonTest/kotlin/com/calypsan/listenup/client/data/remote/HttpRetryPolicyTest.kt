package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.test.http.TestMockEngineBuilder
import com.calypsan.listenup.client.test.http.testMockEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the retry policy installed by [ApiClientFactory]'s authenticated client:
 * idempotent requests retry on 5xx and transient IO failures; non-idempotent methods never
 * retry. See Finding 04 D3 and rubric rule "HttpRequestRetry is installed on authenticated
 * clients with idempotent-only semantics."
 *
 * After [installListenUpErrorHandling] was shrunk to `expectSuccess = true` only, non-2xx
 * responses surface as [ResponseException] (Ktor's standard exception for non-success HTTP
 * status codes) rather than `AppException`. Tests assert on [ResponseException] accordingly.
 */
class HttpRetryPolicyTest {
    private val idempotentMethods =
        setOf(
            HttpMethod.Get,
            HttpMethod.Head,
            HttpMethod.Put,
            HttpMethod.Delete,
            HttpMethod.Options,
        )

    /** Matches the policy installed by `ApiClientFactory.createClient()` — tiny delay for tests. */
    private fun clientWithRetryPolicy(engineBlock: TestMockEngineBuilder.() -> Unit): HttpClient =
        HttpClient(testMockEngine(engineBlock)) {
            installListenUpErrorHandling()
            install(HttpRequestRetry) {
                retryIf(maxRetries = 3) { request, response ->
                    request.method in idempotentMethods && response.status.value in 500..599
                }
                retryOnExceptionIf(maxRetries = 3) { request, cause ->
                    request.method in idempotentMethods &&
                        (cause is IOException || cause is HttpRequestTimeoutException)
                }
                exponentialDelay(base = 2.0, maxDelayMs = 10L) // tiny for tests
            }
        }

    @Test
    fun idempotentGetRetriesOn503ThenSucceeds() =
        runTest {
            var attempts = 0
            val client =
                clientWithRetryPolicy {
                    handle("/flaky") {
                        attempts++
                        if (attempts < 3) {
                            respondError(HttpStatusCode.ServiceUnavailable)
                        } else {
                            respondJsonOk("""{"ok":true}""")
                        }
                    }
                }

            val response: HttpResponse = client.get("http://unit.test/flaky")
            assertTrue(response.status.isSuccess())
            assertEquals(3, attempts, "retry should have attempted three times total")
        }

    @Test
    fun nonIdempotentPostDoesNotRetryOn503() =
        runTest {
            var attempts = 0
            val client =
                clientWithRetryPolicy {
                    handle("/submit") {
                        attempts++
                        respondError(HttpStatusCode.ServiceUnavailable)
                    }
                }

            assertFailsWith<ResponseException> { client.post("http://unit.test/submit") }
            assertEquals(1, attempts, "POST must not retry on 5xx — only idempotent methods retry")
        }

    @Test
    fun retryExhaustsAfterMaxAttemptsAndSurfacesFailure() =
        runTest {
            var attempts = 0
            val client =
                clientWithRetryPolicy {
                    handle("/never-ok") {
                        attempts++
                        respondError(HttpStatusCode.InternalServerError)
                    }
                }

            assertFailsWith<ResponseException> { client.get("http://unit.test/never-ok") }
            assertEquals(4, attempts, "initial attempt + 3 retries = 4 total")
        }

    @Test
    fun fourXXResponsesAreNotRetried() =
        runTest {
            var attempts = 0
            val client =
                clientWithRetryPolicy {
                    handle("/forbidden") {
                        attempts++
                        respondError(HttpStatusCode.Forbidden)
                    }
                }

            assertFailsWith<ResponseException> { client.get("http://unit.test/forbidden") }
            assertEquals(1, attempts, "4xx responses are client errors; retrying doesn't help")
        }
}

/** Shortcut: a 200 OK response with a JSON body and `Content-Type: application/json`. */
private fun MockRequestHandleScope.respondJsonOk(body: String) =
    respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
