package com.calypsan.listenup.server.metadata.custom

import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
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

        test("an oversized answer is abandoned while it downloads, not after the whole body is in memory") {
            runTest {
                val pulled = AtomicLong()
                val producer = CompletableDeferred<Job>()
                val chunk = ByteArray(STREAM_CHUNK_BYTES) { 'a'.code.toByte() }
                val engine =
                    MockEngine {
                        val body =
                            CoroutineScope(Dispatchers.Default).writer {
                                repeat(STREAM_TOTAL_BYTES / STREAM_CHUNK_BYTES) {
                                    channel.writeFully(chunk)
                                    pulled.addAndGet(chunk.size.toLong())
                                }
                            }
                        producer.complete(body.job)
                        respond(body.channel, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
                    }
                val client =
                    CustomMetadataClient(
                        httpClient = HttpClient(engine),
                        json = Json { ignoreUnknownKeys = true },
                        baseUrl = "https://custom.test",
                        rateLimiter = CustomRateLimiter(minInterval = Duration.ZERO),
                        maxBodyBytes = STREAM_CEILING_BYTES,
                    )

                val failure =
                    client.getBook("B01", "The Way of Kings", null, "us").shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.Malformed>()
                // Once the producer has stopped, what it wrote is everything the client ever asked for.
                withContext(Dispatchers.Default) { producer.await().join() }
                pulled.get() shouldBeLessThan STREAM_READ_AHEAD_LIMIT_BYTES
            }
        }
    })

private const val STREAM_CHUNK_BYTES = 8 * 1024
private const val STREAM_CEILING_BYTES = 64L * 1024
private const val STREAM_TOTAL_BYTES = 32 * 1024 * 1024

/** The ceiling plus the few megabytes the channel buffers may let the producer write ahead. */
private const val STREAM_READ_AHEAD_LIMIT_BYTES = 8L * 1024 * 1024
