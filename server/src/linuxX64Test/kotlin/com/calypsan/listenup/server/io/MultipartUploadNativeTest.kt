package com.calypsan.listenup.server.io

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.plugins.installAppErrorStatusPages
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.Test

/**
 * Native counterpart to the JVM upload-route tests: the Kotlin/Native Ktor CIO server cannot parse
 * multipart (KTOR-7361), so [streamFirstFilePartTo]'s native actual throws [MultipartUploadUnsupported],
 * which [installAppErrorStatusPages] surfaces as a precise `501 Not Implemented` rather than an opaque
 * transform error. This proves the upload routes fail honestly on the native runtime (the JVM runtime
 * serves uploads — that path is covered by `BackupRoutesTest` / `ImportRoutesTest`).
 *
 * Drives a real `embeddedServer(CIO)` over an ephemeral port; `kotlin.test.@Test` + `runBlocking` and a
 * `Unit` body so the K/N runner discovers it.
 */
class MultipartUploadNativeTest {
    @Test
    fun nativeMultipartUploadRespondsNotImplemented(): Unit =
        runBlocking {
            val dest = Path("lu-native-multipart-test.bin")
            val server =
                embeddedServer(CIO, port = 0) {
                    install(ContentNegotiation) { json(contractJson) }
                    installAppErrorStatusPages()
                    routing {
                        post("/upload") {
                            val received = call.streamFirstFilePartTo(dest, formFieldLimit = 1L shl 20)
                            call.respondText(if (received) "received" else "empty")
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
                                        "file",
                                        "x".encodeToByteArray(),
                                        Headers.build {
                                            append(HttpHeaders.ContentDisposition, "filename=\"x.bin\"")
                                        },
                                    )
                                },
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.NotImplemented
            } finally {
                client.close()
                server.stop(0, 0)
            }
        }
}
