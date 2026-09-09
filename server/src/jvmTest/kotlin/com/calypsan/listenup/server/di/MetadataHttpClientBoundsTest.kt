package com.calypsan.listenup.server.di

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/** Longer than [SHORT_BUDGET_MS], so the budget decides the outcome rather than the handler. */
private const val UNANSWERED_DELAY_MS = 2_000L
private const val SHORT_BUDGET_MS = 50L

/**
 * Tier-1 transport bounds on the shared metadata [HttpClient] — the client behind every Audible,
 * iTunes, Audnexus, custom-provider, and image request.
 *
 * A provider is best-effort: a remote that accepts a connection and then never answers must not
 * hold a coroutine (and the provider's rate-limiter slot) open forever. These tests pin that the
 * shared configuration carries a bounded budget and that the budget is actually enforced —
 * removing `install(HttpTimeout)` from [installMetadataClientDefaults] fails both.
 *
 * Deliberately *not* asserted here: any host restriction or redirect ban. Those would break
 * self-hosted Audnexus mirrors, operator LAN providers, and Audible's geo-redirect detection; the
 * host policy belongs to Tier 2 ([com.calypsan.listenup.server.metadata.BoundedImageFetch]).
 */
class MetadataHttpClientBoundsTest :
    FunSpec({

        test("every outbound metadata request carries a bounded connect and request budget") {
            var observed: HttpTimeoutConfig? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        observed = request.getCapabilityOrNull(HttpTimeoutCapability)
                        respond(
                            content = "{}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                ) { installMetadataClientDefaults() }

            runTest { client.get("https://provider.example.com/lookup").bodyAsText() }

            val budget = observed.shouldNotBeNull()
            val request = budget.requestTimeoutMillis.shouldNotBeNull()
            val connect = budget.connectTimeoutMillis.shouldNotBeNull()
            request shouldBeGreaterThan 0L
            request shouldBeLessThan HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connect shouldBeGreaterThan 0L
            connect shouldBeLessThan HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }

        test("a request the remote never answers fails instead of waiting forever") {
            // The per-request override only shortens a budget the plugin already enforces: with
            // HttpTimeout absent from the shared configuration nothing reads it and this hangs
            // until the handler finally answers, which is the regression this test exists to catch.
            val client =
                HttpClient(
                    MockEngine {
                        delay(UNANSWERED_DELAY_MS)
                        respond(content = "{}", status = HttpStatusCode.OK)
                    },
                ) { installMetadataClientDefaults() }

            runTest {
                shouldThrow<HttpRequestTimeoutException> {
                    client
                        .get("https://provider.example.com/lookup") {
                            timeout { requestTimeoutMillis = SHORT_BUDGET_MS }
                        }.bodyAsText()
                }
            }
        }
    })
