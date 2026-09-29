package com.calypsan.listenup.server.routes

import com.calypsan.listenup.server.io.writeBytes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

private val RAW = "export const x = 1".encodeToByteArray()
private val BROTLI_BYTES = "BROTLI-BYTES".encodeToByteArray()
private val GZIP_BYTES = "GZIP-BYTES".encodeToByteArray()

/** The one seam the cache promises about: how often the route goes to disk. */
private class CountingFiles : WebBundleFiles {
    var reads = 0
    var stats = 0

    override fun isRegularFile(path: Path): Boolean {
        stats++
        return SystemWebBundleFiles.isRegularFile(path)
    }

    override fun readBytes(path: Path): ByteArray {
        reads++
        return SystemWebBundleFiles.readBytes(path)
    }
}

private fun webRoot(): Path {
    val root = Path(SystemTemporaryDirectory, "webenc-${Random.nextLong().toULong().toString(16)}")
    SystemFileSystem.createDirectories(Path(root, "assets"))
    Path(root, "index.html").writeBytes("<!doctype html><title>ListenUp</title>".encodeToByteArray())
    Path(root, "index.html.br").writeBytes("SHELL-BR".encodeToByteArray())
    Path(root, "assets/app.js").writeBytes(RAW)
    Path(root, "assets/app.js.br").writeBytes(BROTLI_BYTES)
    Path(root, "assets/app.js.gz").writeBytes(GZIP_BYTES)
    // Only a gzip variant: brotli was not smaller, so the build did not emit one.
    Path(root, "assets/app.css").writeBytes("body{}".encodeToByteArray())
    Path(root, "assets/app.css.gz").writeBytes("CSS-GZ".encodeToByteArray())
    // No variants at all, the way a woff2 ships.
    Path(root, "assets/font.woff2").writeBytes(byteArrayOf(1, 2, 3))
    return root
}

/**
 * The web bundle ships precompressed: `vite build` writes `.br` and `.gz` next to each asset, and
 * the route picks the best one the request accepts. Ktor's Compression plugin cannot do this job —
 * it has no linuxX64 artifact, and linuxX64 is the binary that ships — so the route does it by
 * hand, and this spec runs on BOTH lanes to prove it on the native one.
 */
class WebAppEncodingTest :
    FunSpec({
        test("brotli is served when accepted, as the original content type, varying on Accept-Encoding") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root, SystemWebBundleFiles) } }

                val response = client.get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "gzip, br") }

                response.status shouldBe HttpStatusCode.OK
                response.readRawBytes().decodeToString() shouldBe BROTLI_BYTES.decodeToString()
                response.headers[HttpHeaders.ContentEncoding] shouldBe "br"
                response.headers[HttpHeaders.ContentType].shouldNotBeNull().startsWith("application/javascript") shouldBe true
                response.headers[HttpHeaders.Vary] shouldBe HttpHeaders.AcceptEncoding
            }
        }

        test("gzip is served when it is the only compression accepted") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root, SystemWebBundleFiles) } }

                val response = client.get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "gzip") }

                response.readRawBytes().decodeToString() shouldBe GZIP_BYTES.decodeToString()
                response.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
            }
        }

        test("no Accept-Encoding, or identity, gets the raw file with no Content-Encoding") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root, SystemWebBundleFiles) } }

                for (accept in listOf(null, "identity")) {
                    val response =
                        client.get("/assets/app.js") { accept?.let { header(HttpHeaders.AcceptEncoding, it) } }
                    response.readRawBytes().decodeToString() shouldBe RAW.decodeToString()
                    response.headers[HttpHeaders.ContentEncoding].shouldBeNull()
                    // The raw answer is still one of several representations, so caches must key on it.
                    response.headers[HttpHeaders.Vary] shouldBe HttpHeaders.AcceptEncoding
                }
            }
        }

        test("a q=0 exclusion is honoured") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root, SystemWebBundleFiles) } }

                val response = client.get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "br;q=0, gzip") }

                response.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"
            }
        }

        test("an accepted encoding with no variant on disk falls back to what exists") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root, SystemWebBundleFiles) } }

                val css = client.get("/assets/app.css") { header(HttpHeaders.AcceptEncoding, "br, gzip") }
                css.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"

                val font = client.get("/assets/font.woff2") { header(HttpHeaders.AcceptEncoding, "br, gzip") }
                font.readRawBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
                font.headers[HttpHeaders.ContentEncoding].shouldBeNull()
                // One representation only: nothing to vary on.
                font.headers[HttpHeaders.Vary].shouldBeNull()
            }
        }

        test("each encoding has its own ETag") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root, SystemWebBundleFiles) } }

                val raw = client.get("/assets/app.js").headers[HttpHeaders.ETag].shouldNotBeNull()
                val br =
                    client
                        .get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "br") }
                        .headers[HttpHeaders.ETag]
                        .shouldNotBeNull()
                val gz =
                    client
                        .get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "gzip") }
                        .headers[HttpHeaders.ETag]
                        .shouldNotBeNull()

                setOf(raw, br, gz).size shouldBe 3
            }
        }

        test("a brotli ETag does not revalidate a gzip response") {
            // Otherwise a cache holding brotli bytes could be told they are fine for a gzip-only client.
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root, SystemWebBundleFiles) } }

                val br =
                    client
                        .get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "br") }
                        .headers[HttpHeaders.ETag]
                        .shouldNotBeNull()
                val response =
                    client.get("/assets/app.js") {
                        header(HttpHeaders.AcceptEncoding, "gzip")
                        header(HttpHeaders.IfNoneMatch, br)
                    }

                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("a revalidation is a 304 that neither rereads nor rehashes the file") {
            testApplication {
                val root = webRoot()
                val files = CountingFiles()
                application { routing { webAppRoutes(root, files) } }

                val etag =
                    client
                        .get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "br") }
                        .headers[HttpHeaders.ETag]
                        .shouldNotBeNull()
                val readsAfterWarmup = files.reads
                val statsAfterWarmup = files.stats
                readsAfterWarmup shouldNotBe 0

                repeat(3) {
                    val revalidated =
                        client.get("/assets/app.js") {
                            header(HttpHeaders.AcceptEncoding, "br")
                            header(HttpHeaders.IfNoneMatch, etag)
                        }
                    revalidated.status shouldBe HttpStatusCode.NotModified
                    revalidated.headers[HttpHeaders.ETag] shouldBe etag
                    revalidated.headers[HttpHeaders.Vary] shouldBe HttpHeaders.AcceptEncoding
                    revalidated.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"
                }

                files.reads shouldBe readsAfterWarmup
                files.stats shouldBe statsAfterWarmup
            }
        }

        test("a served small file comes from memory after the first request") {
            testApplication {
                val root = webRoot()
                val files = CountingFiles()
                application { routing { webAppRoutes(root, files) } }

                client.get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "br") }
                val readsAfterWarmup = files.reads

                repeat(3) {
                    client
                        .get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "gzip") }
                        .readRawBytes()
                        .decodeToString() shouldBe GZIP_BYTES.decodeToString()
                }

                files.reads shouldBe readsAfterWarmup
            }
        }

        test("the shell stays no-cache, compressed or not, and assets stay immutable") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root, SystemWebBundleFiles) } }

                val shell = client.get("/") { header(HttpHeaders.AcceptEncoding, "br") }
                shell.headers[HttpHeaders.ContentEncoding] shouldBe "br"
                shell.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
                shell.headers[HttpHeaders.ContentType].shouldNotBeNull().startsWith("text/html") shouldBe true

                val deepLink = client.get("/library/some-book") { header(HttpHeaders.AcceptEncoding, "br") }
                deepLink.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
                deepLink.readRawBytes().decodeToString() shouldBe "SHELL-BR"

                val missingAsset = client.get("/assets/gone.js")
                missingAsset.headers[HttpHeaders.CacheControl] shouldBe "no-cache"

                val asset = client.get("/assets/app.js") { header(HttpHeaders.AcceptEncoding, "gzip") }
                asset.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"
            }
        }

        test("compressed responses keep the cross-origin isolation headers") {
            testApplication {
                val root = webRoot()
                application { routing { webAppRoutes(root, SystemWebBundleFiles) } }

                for (path in listOf("/", "/assets/app.js")) {
                    val response = client.get(path) { header(HttpHeaders.AcceptEncoding, "br") }
                    response.headers["Cross-Origin-Opener-Policy"] shouldBe "same-origin"
                    response.headers["Cross-Origin-Embedder-Policy"] shouldBe "require-corp"
                }
            }
        }
    })
