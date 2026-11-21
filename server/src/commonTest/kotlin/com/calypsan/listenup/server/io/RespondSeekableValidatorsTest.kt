package com.calypsan.listenup.server.io

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory

private val original = ByteArray(256) { it.toByte() }

private suspend fun servingFile(
    file: Path,
    block: suspend (HttpClient) -> Unit,
) = testApplication {
    application {
        install(PartialContent)
        routing {
            get("/f") { respondSeekable(call, file, ContentType.Application.OctetStream) }
        }
    }
    block(client)
}

private suspend fun withTempFile(block: suspend (Path) -> Unit) {
    val file = createTempFileIn(SystemTemporaryDirectory, "seekable-validators-", ".bin")
    try {
        file.writeBytes(original)
        block(file)
    } finally {
        SystemFileSystem.delete(file, mustExist = false)
    }
}

/**
 * A partial download can only resume against bytes it can prove have not changed: Apple's
 * NSURLSession refuses to produce resume data without an `ETag` or `Last-Modified`, and every
 * resuming client sends `If-Range` so a file replaced mid-download restarts rather than splicing two
 * different files together. Runs on JVM and linuxX64, the two server targets.
 */
class RespondSeekableValidatorsTest :
    FunSpec({
        test("a file is served with a strong ETag and a Last-Modified") {
            withTempFile { file ->
                servingFile(file) { client ->
                    val resp = client.get("/f")
                    resp.status shouldBe HttpStatusCode.OK
                    resp.headers[HttpHeaders.ETag].shouldNotBeNull().shouldStartWith("\"")
                    resp.headers[HttpHeaders.LastModified].shouldNotBeNull()
                }
            }
        }

        test("the validators are the same on every request for unchanged bytes") {
            withTempFile { file ->
                servingFile(file) { client ->
                    val first = client.get("/f")
                    val second = client.get("/f") { header(HttpHeaders.Range, "bytes=0-9") }
                    second.headers[HttpHeaders.ETag] shouldBe first.headers[HttpHeaders.ETag]
                    second.headers[HttpHeaders.LastModified] shouldBe first.headers[HttpHeaders.LastModified]
                }
            }
        }

        test("the ETag changes when the file is rewritten with different bytes") {
            withTempFile { file ->
                servingFile(file) { client ->
                    val before = client.get("/f").headers[HttpHeaders.ETag]
                    file.writeBytes(ByteArray(300) { 7 })
                    client.get("/f").headers[HttpHeaders.ETag] shouldNotBe before
                }
            }
        }

        test("the ETag changes when the file is replaced by one of the same size") {
            withTempFile { file ->
                servingFile(file) { client ->
                    val before = client.get("/f").headers[HttpHeaders.ETag]
                    file.writeBytesAtomically(ByteArray(original.size) { 7 })
                    client.get("/f").headers[HttpHeaders.ETag] shouldNotBe before
                }
            }
        }

        test("a range whose If-Range names the current ETag is answered with that range") {
            withTempFile { file ->
                servingFile(file) { client ->
                    val etag = client.get("/f").headers[HttpHeaders.ETag].shouldNotBeNull()
                    val resp =
                        client.get("/f") {
                            header(HttpHeaders.Range, "bytes=100-149")
                            header(HttpHeaders.IfRange, etag)
                        }
                    resp.status shouldBe HttpStatusCode.PartialContent
                    resp.headers[HttpHeaders.ContentRange] shouldBe "bytes 100-149/256"
                    resp.readRawBytes().toList() shouldBe original.copyOfRange(100, 150).toList()
                }
            }
        }

        test("a range whose If-Range names the current Last-Modified is answered with that range") {
            withTempFile { file ->
                servingFile(file) { client ->
                    val lastModified = client.get("/f").headers[HttpHeaders.LastModified].shouldNotBeNull()
                    val resp =
                        client.get("/f") {
                            header(HttpHeaders.Range, "bytes=100-149")
                            header(HttpHeaders.IfRange, lastModified)
                        }
                    resp.status shouldBe HttpStatusCode.PartialContent
                    resp.headers[HttpHeaders.ContentRange] shouldBe "bytes 100-149/256"
                }
            }
        }

        test("a range whose If-Range names an older version gets the whole current file") {
            withTempFile { file ->
                servingFile(file) { client ->
                    val stale = client.get("/f").headers[HttpHeaders.ETag].shouldNotBeNull()
                    val replacement = ByteArray(300) { 7 }
                    file.writeBytes(replacement)
                    val resp =
                        client.get("/f") {
                            header(HttpHeaders.Range, "bytes=100-149")
                            header(HttpHeaders.IfRange, stale)
                        }
                    resp.status shouldBe HttpStatusCode.OK
                    resp.readRawBytes().toList() shouldBe replacement.toList()
                }
            }
        }

        // Deliberately no 304 (the server installs no ConditionalHeaders): a player whose HTTP cache
        // revalidates a partially-fetched file must still be handed bytes it can play.
        test("If-None-Match naming the current ETag still streams the file") {
            withTempFile { file ->
                servingFile(file) { client ->
                    val etag = client.get("/f").headers[HttpHeaders.ETag].shouldNotBeNull()
                    val resp = client.get("/f") { header(HttpHeaders.IfNoneMatch, etag) }
                    resp.status shouldBe HttpStatusCode.OK
                    resp.readRawBytes().toList() shouldBe original.toList()
                }
            }
        }
    })
