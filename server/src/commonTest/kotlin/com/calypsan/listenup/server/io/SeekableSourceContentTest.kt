package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
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

private val payload = ByteArray(256) { it.toByte() }

private suspend fun serving(block: suspend (HttpClient) -> Unit) =
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

/**
 * Proves the streaming file-response seam serves on JVM **and** linuxX64: [SeekableSourceContent] over
 * the native [SeekableSource] seam, sliced by `PartialContent` — `200` full body and `206` exact
 * sub-range, with the windowed bytes produced by a real seek (not loaded whole). The native run is the
 * "serves native" proof that `respondSeekable` can replace `LocalFileContent`/`respondFile`.
 */
class SeekableSourceContentTest :
    FunSpec({
        test("full request streams the whole file as 200") {
            serving { client ->
                val resp = client.get("/f")
                resp.status shouldBe HttpStatusCode.OK
                resp.readRawBytes().toList() shouldBe payload.toList()
            }
        }

        test("range request streams the exact window as 206") {
            serving { client ->
                val resp = client.get("/f") { header(HttpHeaders.Range, "bytes=100-149") }
                resp.status shouldBe HttpStatusCode.PartialContent
                resp.headers[HttpHeaders.ContentRange] shouldBe "bytes 100-149/256"
                resp.readRawBytes().toList() shouldBe payload.copyOfRange(100, 150).toList()
            }
        }
    })
