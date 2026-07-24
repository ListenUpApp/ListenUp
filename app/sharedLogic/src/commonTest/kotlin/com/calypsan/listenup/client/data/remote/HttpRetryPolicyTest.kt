package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.client.test.http.TestMockEngineBuilder
import com.calypsan.listenup.client.test.http.testMockEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

/**
 * Verifies the retry policy installed by [ApiClientFactory]'s authenticated client:
 * idempotent requests retry on 5xx and transient IO failures; non-idempotent methods never
 * retry: "HttpRequestRetry is installed on authenticated
 * clients with idempotent-only semantics."
 *
 * After [installListenUpErrorHandling] was shrunk to `expectSuccess = true` only, non-2xx
 * responses surface as [ResponseException] (Ktor's standard exception for non-success HTTP
 * status codes) rather than `AppException`. Tests assert on [ResponseException] accordingly.
 */
class HttpRetryPolicyTest :
    FunSpec({
        val idempotentMethods =
            setOf(
                HttpMethod.Get,
                HttpMethod.Head,
                HttpMethod.Put,
                HttpMethod.Delete,
                HttpMethod.Options,
            )

        // Matches the policy installed by `ApiClientFactory.createClient()` — tiny delay for tests.
        fun clientWithRetryPolicy(engineBlock: TestMockEngineBuilder.() -> Unit): HttpClient =
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

        test("idempotentGetRetriesOn503ThenSucceeds") {
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
                response.status.isSuccess() shouldBe true
                withClue("retry should have attempted three times total") { attempts shouldBe 3 }
            }
        }

        test("nonIdempotentPostDoesNotRetryOn503") {
            runTest {
                var attempts = 0
                val client =
                    clientWithRetryPolicy {
                        handle("/submit") {
                            attempts++
                            respondError(HttpStatusCode.ServiceUnavailable)
                        }
                    }

                shouldThrow<ResponseException> { client.post("http://unit.test/submit") }
                withClue("POST must not retry on 5xx — only idempotent methods retry") { attempts shouldBe 1 }
            }
        }

        test("retryExhaustsAfterMaxAttemptsAndSurfacesFailure") {
            runTest {
                var attempts = 0
                val client =
                    clientWithRetryPolicy {
                        handle("/never-ok") {
                            attempts++
                            respondError(HttpStatusCode.InternalServerError)
                        }
                    }

                shouldThrow<ResponseException> { client.get("http://unit.test/never-ok") }
                withClue("initial attempt + 3 retries = 4 total") { attempts shouldBe 4 }
            }
        }

        test("fourXXResponsesAreNotRetried") {
            runTest {
                var attempts = 0
                val client =
                    clientWithRetryPolicy {
                        handle("/forbidden") {
                            attempts++
                            respondError(HttpStatusCode.Forbidden)
                        }
                    }

                shouldThrow<ResponseException> { client.get("http://unit.test/forbidden") }
                withClue("4xx responses are client errors; retrying doesn't help") { attempts shouldBe 1 }
            }
        }
    })

/** Shortcut: a 200 OK response with a JSON body and `Content-Type: application/json`. */
private fun MockRequestHandleScope.respondJsonOk(body: String) =
    respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
