package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.appJson
import com.calypsan.listenup.client.test.http.TestMockEngineBuilder
import com.calypsan.listenup.client.test.http.testMockEngine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString

/**
 * Locks two boundary guarantees of [apiCall]:
 *
 * 1. A 200 response whose envelope this client can't parse (Finding #2) surfaces as a typed
 *    [AppResult.Failure], never an exception escaping past the AppResult boundary.
 * 2. A non-2xx response carrying a serialized [com.calypsan.listenup.api.error.AppError] body
 *    (Finding #4) is decoded back into that exact typed error — preserving `correlationId`,
 *    `isRetryable`, and per-instance fields — instead of being flattened to a generic
 *    status-based `Server4xx`/`Server5xx`.
 */
class ApiCallTypedFailureTest :
    FunSpec({
        fun client(block: TestMockEngineBuilder.() -> Unit): HttpClient =
            HttpClient(testMockEngine(block)) {
                install(ContentNegotiation) { json(appJson) }
                installListenUpErrorHandling()
            }

        // ---- Finding #2: bad envelope on a 200 must not escape past AppResult ----

        test("200 with envelope missing 'v' yields a typed ContractMismatch failure, not a throw") {
            runTest {
                val c = client { respondJson("/genres") { """{"success":true,"data":"payload"}""" } }

                val result =
                    apiCall<String>(errorMessage = "Failed to load") {
                        c.get("http://unit.test/genres").body()
                    }

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<TransportError.ContractMismatch>()
            }
        }

        test("200 with wrong envelope version yields a typed ContractMismatch failure") {
            runTest {
                val c = client { respondJson("/genres") { """{"v":999,"success":true,"data":"payload"}""" } }

                val result =
                    apiCall<String>(errorMessage = "Failed to load") {
                        c.get("http://unit.test/genres").body()
                    }

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<TransportError.ContractMismatch>()
            }
        }

        // ---- Finding #4: typed AppError error bodies are decoded, not discarded ----

        test("non-2xx body carrying a bare AppError is decoded to that exact typed error") {
            runTest {
                val rateLimited =
                    AuthError.RateLimited(correlationId = "cid-abc-123", retryAfterSeconds = 42)
                val body = appJson.encodeToString<com.calypsan.listenup.api.error.AppError>(rateLimited)
                val c = client { respondJson("/login", HttpStatusCode.TooManyRequests) { body } }

                val result =
                    apiCall<String>(errorMessage = "Failed to log in") {
                        c.get("http://unit.test/login").body()
                    }

                result.shouldBeInstanceOf<AppResult.Failure>()
                val error = result.error.shouldBeInstanceOf<AuthError.RateLimited>()
                error.correlationId shouldBe "cid-abc-123"
                error.retryAfterSeconds shouldBe 42
                error.isRetryable shouldBe true
            }
        }

        test("non-2xx body carrying an AppResult.Failure envelope is decoded to its typed error") {
            runTest {
                val rateLimited =
                    AuthError.RateLimited(correlationId = "cid-wrap-9", retryAfterSeconds = 7)
                val body = appJson.encodeToString<AppResult<String>>(AppResult.Failure(rateLimited))
                val c = client { respondJson("/login", HttpStatusCode.TooManyRequests) { body } }

                val result =
                    apiCall<String>(errorMessage = "Failed to log in") {
                        c.get("http://unit.test/login").body()
                    }

                result.shouldBeInstanceOf<AppResult.Failure>()
                val error = result.error.shouldBeInstanceOf<AuthError.RateLimited>()
                error.correlationId shouldBe "cid-wrap-9"
                error.retryAfterSeconds shouldBe 7
            }
        }

        test("non-2xx body that is not an AppError falls back to status-based mapping") {
            runTest {
                val c = client { respondJson("/boom", HttpStatusCode.InternalServerError) { "gateway timeout" } }

                val result =
                    apiCall<String>(errorMessage = "Failed") {
                        c.get("http://unit.test/boom").body()
                    }

                result.shouldBeInstanceOf<AppResult.Failure>()
                val error = result.error.shouldBeInstanceOf<TransportError.Server5xx>()
                error.statusCode shouldBe 500
            }
        }
    })
