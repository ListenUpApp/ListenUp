package com.calypsan.listenup.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel

/** The book cover cap the route passes to [receiveFramedImage] (10 MiB). */
private const val COVER_CAP = 10L * 1024 * 1024

/**
 * Proves the decoder `PUT /api/v1/books/{id}/cover` reads through works on the Kotlin/Native CIO server, where Ktor's
 * `receiveMultipart` does not (KTOR-7361). Drives [receiveFramedImage] — the call the cover route makes, with the same
 * cap — over a real `embeddedServer(CIO)`, with the body shaped the way the client's `ImageApi.uploadBookCover` sends
 * it: one `file` part carrying a filename and a wildcard image content type.
 */
class CoverUploadDecoderNativeTest :
    FunSpec({
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
        var received: ByteArray? = null

        suspend fun withCoverServer(block: suspend (HttpClient, Int) -> Unit) {
            val server =
                embeddedServer(CIO, port = 0) {
                    routing {
                        put("/cover") {
                            when (val upload = receiveFramedImage(call, COVER_CAP, "cover")) {
                                is FramedUpload.Received -> {
                                    received = upload.bytes
                                    call.respond(HttpStatusCode.NoContent)
                                }

                                is ImageUploadOutcome.Rejected -> {
                                    call.respond(upload.status, upload.message)
                                }

                                is ImageUploadOutcome.InvalidFrame -> {
                                    call.respond(HttpStatusCode.BadRequest)
                                }
                            }
                        }
                    }
                }
            server.start(wait = false)
            val client = HttpClient(ClientCIO)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                block(client, port)
            } finally {
                client.close()
                server.stop(0, 0)
            }
        }

        suspend fun HttpClient.putCover(
            port: Int,
            file: ChannelProvider,
        ): HttpResponse =
            put("http://127.0.0.1:$port/cover") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                file,
                                Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"cover.png\"")
                                    append(HttpHeaders.ContentType, "image/*")
                                },
                            )
                        },
                    ),
                )
            }

        test("native server reads a book cover upload's file part") {
            withCoverServer { client, port ->
                val response = client.putCover(port, ChannelProvider(size = png.size.toLong()) { ByteReadChannel(png) })

                response.status shouldBe HttpStatusCode.NoContent
                received shouldBe png
            }
        }
    })
