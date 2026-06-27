package com.calypsan.listenup.server.io

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Proves the streaming file-response seam serves on JVM **and** linuxX64: [SeekableSourceContent] over
 * the native [SeekableSource] seam, sliced by `PartialContent` — `200` full body and `206` exact
 * sub-range, with the windowed bytes produced by a real seek (not loaded whole). The native run is the
 * "serves native" proof that `respondSeekable` can replace `LocalFileContent`/`respondFile`.
 */
class SeekableSourceContentTest {
    private val payload = ByteArray(256) { it.toByte() }

    private fun serving(block: suspend (HttpClient) -> Unit) =
        runBlocking {
            testApplication {
                application {
                    install(PartialContent)
                    routing {
                        get("/f") {
                            call.respond(
                                SeekableSourceContent(
                                    length = payload.size.toLong(),
                                    contentType = ContentType.Application.OctetStream,
                                ) { ByteArraySeekableSource(payload) },
                            )
                        }
                    }
                }
                block(client)
            }
        }

    @Test
    fun fullRequestStreamsWholeFileAs200(): Unit =
        serving { client ->
            val resp = client.get("/f")
            resp.status shouldBe HttpStatusCode.OK
            resp.readRawBytes().toList() shouldBe payload.toList()
        }

    @Test
    fun rangeRequestStreamsExactWindowAs206(): Unit =
        serving { client ->
            val resp = client.get("/f") { header(HttpHeaders.Range, "bytes=100-149") }
            resp.status shouldBe HttpStatusCode.PartialContent
            resp.headers[HttpHeaders.ContentRange] shouldBe "bytes 100-149/256"
            resp.readRawBytes().toList() shouldBe payload.copyOfRange(100, 150).toList()
        }
}
