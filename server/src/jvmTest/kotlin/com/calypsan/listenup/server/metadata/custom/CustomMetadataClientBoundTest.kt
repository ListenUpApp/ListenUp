package com.calypsan.listenup.server.metadata.custom

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.time.Duration

class CustomMetadataClientBoundTest :
    FunSpec({
        test("a provider answer over the ceiling is Malformed") {
            runTest {
                val engine =
                    MockEngine {
                        respond(
                            """{"title":"The Way of Kings","description":"Stone and storms."}""",
                            HttpStatusCode.OK,
                            headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    }
                val client =
                    CustomMetadataClient(
                        httpClient = HttpClient(engine),
                        json = Json { ignoreUnknownKeys = true },
                        baseUrl = "https://custom.test",
                        rateLimiter = CustomRateLimiter(minInterval = Duration.ZERO),
                        maxBodyBytes = 16,
                    )

                val failure =
                    client.getBook("B01", "The Way of Kings", null, "us").shouldBeInstanceOf<AppResult.Failure>()
                val error = failure.error.shouldBeInstanceOf<MetadataError.Malformed>()
                error.debugInfo shouldBe "response body over 16 bytes"
            }
        }
    })
