package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.installCorePlugins
import com.calypsan.listenup.server.io.createTempFileIn
import com.calypsan.listenup.server.io.respondSeekable
import com.calypsan.listenup.server.io.writeBytes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

private val audioBytes = ByteArray(256) { it.toByte() }

/**
 * `HEAD` on a streamed audio file, through the plugin set the real server installs.
 *
 * Players and download managers ask `HEAD` before committing to a `GET` — for the length, the
 * validators a resume needs, and whether ranges are honoured. Every `GET` route answers it only
 * because the application installs `AutoHeadResponse`, and the native server once installed a no-op
 * in its place, so a `HEAD` there fell through to `405`. Runs on JVM and linuxX64, so the server
 * users actually run (the native binary) is held to the same answer as the JVM one.
 */
class AudioHeadRequestTest :
    FunSpec({
        test("HEAD on an audio file answers the GET's headers with no body") {
            val file = createTempFileIn(SystemTemporaryDirectory, "audio-head-", ".m4b")
            try {
                file.writeBytes(audioBytes)
                testApplication {
                    application {
                        installCorePlugins()
                        routing {
                            get("/audio") { respondSeekable(call, file, audioContentType("m4b")) }
                        }
                    }

                    val get = client.get("/audio")
                    val head = client.head("/audio")

                    head.status shouldBe HttpStatusCode.OK
                    head.headers[HttpHeaders.ContentLength] shouldBe audioBytes.size.toString()
                    head.headers[HttpHeaders.AcceptRanges] shouldBe "bytes"
                    head.headers[HttpHeaders.ContentType] shouldBe "audio/mp4"
                    head.headers[HttpHeaders.ETag].shouldNotBeNull().shouldStartWith("\"")
                    head.headers[HttpHeaders.ETag] shouldBe get.headers[HttpHeaders.ETag]
                    head.headers[HttpHeaders.LastModified] shouldBe get.headers[HttpHeaders.LastModified]
                    head.readRawBytes().size shouldBe 0
                }
            } finally {
                SystemFileSystem.delete(file, mustExist = false)
            }
        }
    })
