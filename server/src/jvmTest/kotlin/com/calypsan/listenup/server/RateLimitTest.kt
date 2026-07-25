package com.calypsan.listenup.server

import com.calypsan.listenup.server.testing.publicAuthService

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
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Verifies the `/login` rate-limit bucket fires after 10 requests in one minute.
 * Expansion to other buckets is straightforward but redundant — the wiring is
 * the same per-bucket; this test proves the wiring works.
 */
class RateLimitTest :
    FunSpec({
        test("the (limit+1)th login from one host is throttled over the RPC surface") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                // ONE proxy over ONE connection — a real client holds a single channel, and the
                // per-IP bucket is keyed on the remote host bound at registration time.
                val auth = publicAuthService()

                repeat(LOGIN_BUCKET_LIMIT) {
                    auth.login(LoginRequest("nobody@x", "x".repeat(8)))
                }

                auth
                    .login(LoginRequest("nobody@x", "x".repeat(8)))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<AuthError.RateLimited>()
            }
        }
    })

private const val LOGIN_BUCKET_LIMIT = 10
