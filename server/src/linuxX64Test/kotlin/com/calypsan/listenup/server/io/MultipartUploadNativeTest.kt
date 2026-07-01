package com.calypsan.listenup.server.io

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.test.Test

/**
 * Proves the Kotlin/Native runtime serves multipart uploads at parity with the JVM. Ktor's CIO server
 * cannot use `receiveMultipart` (KTOR-7361), so [streamFirstFilePartTo]'s native actual reads the raw
 * body channel and decodes the wire format with [streamFirstFilePart] (whose every branch is covered
 * by `MultipartFormDataTest` on the JVM runner). This drives a real `embeddedServer(CIO)` over an
 * ephemeral port and asserts the uploaded file lands on disk byte-for-byte.
 *
 * `kotlin.test.@Test` + `runBlocking` and a `Unit` body so the K/N runner discovers it; the upload
 * target is a CWD-relative path because the native test runner has no usable temp directory.
 */
class MultipartUploadNativeTest {
    private companion object {
        const val RECEIVED = "received"
        const val EMPTY = "empty"
    }

    @Test
    fun nativeServerStreamsTheFirstFilePartToDisk(): Unit =
        runBlocking {
            val dest = Path("lu-native-multipart-upload-test.bin")
            val payload = ByteArray(50_000) { (it * 7).toByte() }
            val server =
                embeddedServer(CIO, port = 0) {
                    routing {
                        post("/upload") {
                            val received = call.streamFirstFilePartTo(dest, formFieldLimit = 1L shl 20)
                            call.respondText(if (received) RECEIVED else EMPTY)
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
                val response =
                    client.post("http://127.0.0.1:$port/upload") {
                        setBody(
                            MultiPartFormDataContent(
                                formData {
                                    append(
                                        "backup",
                                        payload,
                                        Headers.build {
                                            append(HttpHeaders.ContentDisposition, "filename=\"lib.zip\"")
                                        },
                                    )
                                },
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe RECEIVED
                SystemFileSystem.source(dest).buffered().use { it.readByteArray() } shouldBe payload
            } finally {
                client.close()
                server.stop(0, 0)
                SystemFileSystem.delete(dest, mustExist = false)
            }
        }

    @Test
    fun nativeServerReadsTheFirstFilePartIntoMemory(): Unit =
        runBlocking {
            // The avatar route needs the first file part's bytes in memory (magic-number validation),
            // not a stream to disk. The Kotlin/Native CIO server can't use `receiveMultipart`
            // (KTOR-7361), so this exercises the raw-channel path over a real embeddedServer(CIO).
            val payload = ByteArray(40_000) { (it * 13).toByte() }
            var received: ByteArray? = null
            val server =
                embeddedServer(CIO, port = 0) {
                    routing {
                        post("/avatar") {
                            received = call.receiveFirstFilePartBytes(formFieldLimit = 1L shl 20)
                            call.respondText(if (received != null) RECEIVED else EMPTY)
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
                val response =
                    client.post("http://127.0.0.1:$port/avatar") {
                        setBody(
                            MultiPartFormDataContent(
                                formData {
                                    append(
                                        "file",
                                        payload,
                                        Headers.build {
                                            append(HttpHeaders.ContentDisposition, "filename=\"avatar.png\"")
                                        },
                                    )
                                },
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe RECEIVED
                received shouldBe payload
            } finally {
                client.close()
                server.stop(0, 0)
            }
        }
}
