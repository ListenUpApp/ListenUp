package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.test.http.testMockEngine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.test.runTest

/**
 * Verifies that [installListenUpErrorHandling] sets `expectSuccess = true` so that
 * non-2xx responses surface as [ResponseException] (Ktor's standard exception for
 * non-success HTTP status codes).
 *
 * Error mapping (ResponseException → typed AppError) happens at the `suspendRunCatching { ... }`
 * boundary, not inside the plugin itself. These tests verify the plugin's single
 * responsibility: raise on non-2xx rather than passing error bodies to the decoder.
 */
class HttpClientErrorHandlingTest :
    FunSpec({
        fun client(block: com.calypsan.listenup.client.test.http.TestMockEngineBuilder.() -> Unit): HttpClient =
            HttpClient(testMockEngine(block)) {
                installListenUpErrorHandling()
            }

        test("successfulResponsePassesThrough") {
            runTest {
                val c = client { respondJson("/ok") { """{"status":"ok"}""" } }

                val response = c.get("http://unit.test/ok")
                response.status.isSuccess() shouldBe true
                response.bodyAsText() shouldBe """{"status":"ok"}"""
            }
        }

        test("serverErrorResponseRaisesResponseException") {
            runTest {
                // expectSuccess = true means Ktor raises ResponseException on non-2xx.
                // The exception type is ResponseException (or a ServerResponseException subtype).
                val c = client { respondStatus("/boom", HttpStatusCode.InternalServerError) }

                val thrown = shouldThrow<ResponseException> { c.get("http://unit.test/boom") }
                thrown.response.status.value shouldBe 500
            }
        }

        test("unauthorizedResponseRaisesResponseException") {
            runTest {
                val c = client { respondStatus("/secret", HttpStatusCode.Unauthorized) }

                val thrown = shouldThrow<ResponseException> { c.get("http://unit.test/secret") }
                thrown.response.status.value shouldBe 401
            }
        }

        test("notFoundResponseRaisesResponseException") {
            runTest {
                val c = client { respondStatus("/missing", HttpStatusCode.NotFound) }

                val thrown = shouldThrow<ResponseException> { c.get("http://unit.test/missing") }
                thrown.response.status.value shouldBe 404
            }
        }

        test("suspendRunCatchingCatchesResponseExceptionAndProducesTypedFailure") {
            runTest {
                // End-to-end: installListenUpErrorHandling raises ResponseException on non-2xx;
                // suspendRunCatching catches it; ErrorMapper produces a typed AppError.
                // This validates the full boundary contract without reaching any server.
                val c = client { respondStatus("/boom", HttpStatusCode.InternalServerError) }

                val result =
                    suspendRunCatching<String> {
                        c.get("http://unit.test/boom").body()
                    }

                result.shouldBeInstanceOf<AppResult.Failure>()
                val error = result.error
                error.shouldBeInstanceOf<TransportError.Server5xx>()
                error.statusCode shouldBe 500
            }
        }

        test("suspendRunCatchingCatchesUnauthorizedAndProducesTypedSessionExpiredFailure") {
            runTest {
                // 401 Unauthorized → ErrorMapper upgrades to AuthError.SessionExpired at the
                // boundary (a stale/invalid session). This is the typed signal that the global
                // auth-failure observer reacts to by driving the app back to the login screen.
                val c = client { respondStatus("/secret", HttpStatusCode.Unauthorized) }

                val result =
                    suspendRunCatching<String> {
                        c.get("http://unit.test/secret").body()
                    }

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<AuthError.SessionExpired>()
            }
        }

        test("suspendRunCatchingCatchesForbiddenAndProducesTypedServer4xxFailure") {
            runTest {
                // 403 Forbidden → ErrorMapper classifies as TransportError.Server4xx(403).
                // Auth-specific upgrade (to AuthError) happens at the repository layer.
                val c = client { respondStatus("/forbidden", HttpStatusCode.Forbidden) }

                val result =
                    suspendRunCatching<String> {
                        c.get("http://unit.test/forbidden").body()
                    }

                result.shouldBeInstanceOf<AppResult.Failure>()
                val error = result.error
                error.shouldBeInstanceOf<TransportError.Server4xx>()
                error.statusCode shouldBe 403
            }
        }
    })
