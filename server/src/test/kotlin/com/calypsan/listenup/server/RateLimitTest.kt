package com.calypsan.listenup.server

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication

/**
 * Verifies the `/login` rate-limit bucket fires after 10 requests in one minute.
 * Expansion to other buckets is straightforward but redundant — the wiring is
 * the same per-bucket; this test proves the wiring works.
 */
class RateLimitTest :
    FunSpec({
        test("11th /login within a minute returns 429 TooManyRequests") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }

                repeat(LOGIN_BUCKET_LIMIT) {
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(LoginRequest("nobody@x", "x".repeat(8)))
                    }
                }

                val r =
                    client.post("/api/v1/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody(LoginRequest("nobody@x", "x".repeat(8)))
                    }

                r.status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })

private const val LOGIN_BUCKET_LIMIT = 10
