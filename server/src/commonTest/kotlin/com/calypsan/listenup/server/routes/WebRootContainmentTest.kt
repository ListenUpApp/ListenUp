package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.io.writeBytes
import com.calypsan.listenup.server.librarywrite.createSymbolicLink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

private const val OUTSIDE = "OUTSIDE-THE-WEB-ROOT"
private const val SHELL = "<!doctype html><title>ListenUp</title>"

/** A web root at `<base>/web`, with files beside it and in a sibling directory that it must never serve. */
private class Fixture {
    val base = Path(SystemTemporaryDirectory, "webroot-${Random.nextLong().toULong().toString(16)}")
    val root = Path(base, "web")

    init {
        SystemFileSystem.createDirectories(Path(root, "assets"))
        SystemFileSystem.createDirectories(Path(base, "home"))
        Path(root, "index.html").writeBytes(SHELL.encodeToByteArray())
        Path(root, "assets/app.js").writeBytes("export const x = 1".encodeToByteArray())
        Path(root, "assets/app.js.br").writeBytes("APP-BR".encodeToByteArray())
        Path(root, "assets/app.js.gz").writeBytes("APP-GZ".encodeToByteArray())
        Path(base, "canary.txt").writeBytes(OUTSIDE.encodeToByteArray())
        Path(base, "home/listenup.db").writeBytes(OUTSIDE.encodeToByteArray())
    }
}

/**
 * The web client route builds a filesystem path from the request path, so it is the one place a
 * URL decides which file is read. Ktor splits the path on raw `/` and then percent-decodes each
 * segment, so an encoded separator arrives *inside* a segment. Every request here must stay inside
 * the web root — and this spec runs on both lanes, because the native binary is what ships.
 */
class WebRootContainmentTest :
    FunSpec({
        listOf(
            "/..%2fcanary.txt",
            "/..%2Fcanary.txt",
            "/%2e%2e%2fcanary.txt",
            "/%2e%2e/canary.txt",
            "/assets/..%2f..%2fcanary.txt",
            "/%2e%2e%2fhome%2flistenup.db",
            "/" + "%2e%2e%2f".repeat(8) + "etc%2fpasswd",
            "/..%5ccanary.txt",
            "/assets%2fapp.js",
            "/%2e",
            "/assets/app.js%00.txt",
        ).forEach { requestPath ->
            test("$requestPath is not found") {
                testApplication {
                    val fixture = Fixture()
                    application { routing { webAppRoutes(fixture.root, SystemWebBundleFiles) } }

                    val response = client.get(requestPath)

                    response.bodyAsText() shouldNotContain OUTSIDE
                    response.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("the bundle itself refuses a path outside the root, whatever the route lets through") {
            val fixture = Fixture()
            val bundle = WebBundle(fixture.root, SystemWebBundleFiles)

            bundle.lookup(listOf("..", "canary.txt")).shouldBeNull()
            bundle.lookup(listOf("../canary.txt")).shouldBeNull()
            bundle.lookup(listOf("assets", "..", "..", "home", "listenup.db")).shouldBeNull()
            bundle.lookup(listOf("assets", "app.js")).shouldNotBeNull()
        }

        test("a nested asset is still served, with its precompressed variants") {
            testApplication {
                val fixture = Fixture()
                application { routing { webAppRoutes(fixture.root, SystemWebBundleFiles) } }

                client.get("/assets/app.js").bodyAsText() shouldBe "export const x = 1"
                client
                    .get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "br") }
                    .readRawBytes()
                    .decodeToString() shouldBe "APP-BR"
                client
                    .get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "gzip") }
                    .readRawBytes()
                    .decodeToString() shouldBe "APP-GZ"
            }
        }

        test("the shell is still served at the root and for a client-side route") {
            testApplication {
                val fixture = Fixture()
                application { routing { webAppRoutes(fixture.root, SystemWebBundleFiles) } }

                for (path in listOf("/", "/library/some-book", "/library/")) {
                    val response = client.get(path)
                    response.status shouldBe HttpStatusCode.OK
                    response.bodyAsText() shouldBe SHELL
                }
            }
        }

        test("a symbolic link inside the web root that points outside it is not served") {
            testApplication {
                val fixture = Fixture()
                createSymbolicLink(Path(fixture.root, "assets/linked.js"), Path(fixture.base, "canary.txt"))
                createSymbolicLink(Path(fixture.root, "assets/home"), Path(fixture.base, "home"))
                application { routing { webAppRoutes(fixture.root, SystemWebBundleFiles) } }

                for (path in listOf("/assets/linked.js", "/assets/home/listenup.db")) {
                    client.get(path).bodyAsText() shouldBe SHELL
                }
            }
        }

        test("a precompressed variant linked outside the web root is not served") {
            testApplication {
                val fixture = Fixture()
                SystemFileSystem.delete(Path(fixture.root, "assets/app.js.br"))
                createSymbolicLink(Path(fixture.root, "assets/app.js.br"), Path(fixture.base, "canary.txt"))
                application { routing { webAppRoutes(fixture.root, SystemWebBundleFiles) } }

                val response = client.get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "br, gzip") }

                response.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
                response.readRawBytes().decodeToString() shouldBe "APP-GZ"
            }
        }

        test("a web root reached through a symbolic link still serves its own files") {
            testApplication {
                val fixture = Fixture()
                val linkedRoot = Path(fixture.base, "web-link")
                createSymbolicLink(linkedRoot, fixture.root)
                application { routing { webAppRoutes(linkedRoot, SystemWebBundleFiles) } }

                client.get("/assets/app.js").bodyAsText() shouldBe "export const x = 1"
                client.get("/").bodyAsText() shouldBe SHELL
            }
        }
    })
