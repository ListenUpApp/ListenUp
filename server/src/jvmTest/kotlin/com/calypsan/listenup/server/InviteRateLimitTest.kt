package com.calypsan.listenup.server

import com.calypsan.listenup.api.dto.invite.ClaimInviteRequest
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
 * Proves the public invite claim endpoint is rate-limited — the Argon2/account-creation
 * surface must not be an unbounded anonymous CPU sink. A bogus code trips the limiter before
 * any hashing runs (the limiter is applied at the route, ahead of the handler), so this stays
 * cheap. Mirrors [RateLimitTest]: one test proves the per-bucket wiring; the sibling lookup
 * bucket is wired identically.
 */
class InviteRateLimitTest :
    FunSpec({
        test("claim beyond the bucket limit returns 429 TooManyRequests") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }
                val client = createClient { install(ContentNegotiation) { json() } }

                repeat(INVITE_CLAIM_BUCKET_LIMIT) {
                    client.post("/api/v1/invites/bogus-code/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(ClaimInviteRequest(password = "x".repeat(8), displayName = "Nobody", deviceInfo = null))
                    }
                }

                val r =
                    client.post("/api/v1/invites/bogus-code/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(ClaimInviteRequest(password = "x".repeat(8), displayName = "Nobody", deviceInfo = null))
                    }

                r.status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })

private const val INVITE_CLAIM_BUCKET_LIMIT = 5
